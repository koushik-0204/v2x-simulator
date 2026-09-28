package com.v2x.rsu;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Autonomous V2X traffic simulation engine.
 *
 * Runs independently of any external UDP node. Vehicles are spawned automatically,
 * driven by the Intelligent Driver Model (IDM), perform V2V collision avoidance,
 * autonomous lane changes, emergency vehicle corridor yielding, realistic wear
 * degradation, and shoulder pull-over on breakdown.
 *
 * When an external UDP node sends data (via UdpListenerService), those vehicles
 * co-exist seamlessly with simulated ones in the shared vehicle map.
 */
@Service
public class TrafficSimulationService {

    // ── Simulation parameters ────────────────────────────────────────────────
    private static final int TICK_MS = 50;           // 20 Hz physics loop
    private static final double DT = TICK_MS / 1000.0; // seconds per tick

    private static final double ROAD_START_X    = 0.0;
    private static final double ROAD_LENGTH_M   = 2000.0; // virtual highway meters
    private static final double SPAWN_X         = 0.0;
    private static final double DESPAWN_X       = ROAD_LENGTH_M;

    // Traffic density limits
    private static final int DENSITY_LIGHT  = 3;
    private static final int DENSITY_MEDIUM = 6;
    private static final int DENSITY_RUSH   = 10;

    // IDM constants — larger gaps make the simulation visually readable
    private static final double IDM_DESIRED_TIME_GAP  = 4.5;   // s (comfortable spacing)
    private static final double IDM_MIN_GAP            = 15.0;  // m (bumper-to-bumper min)
    private static final double IDM_DELTA              = 4.0;
    private static final double IDM_COMFORTABLE_DECEL  = 2.5;   // m/s²

    // Lane definitions: 0=left-fast, 1=middle, 2=right-slow, 3=shoulder(parked)
    private static final int NUM_TRAFFIC_LANES = 3;

    // ── Shared state ─────────────────────────────────────────────────────────
    private final UdpListenerService udpListenerService;

    /** The simulation's own vehicles (id → state). */
    private final ConcurrentHashMap<String, VehicleState> simVehicles = new ConcurrentHashMap<>();

    /** Per-vehicle internal physics context (not serialised to dashboard). */
    private final ConcurrentHashMap<String, VehiclePhysics> physics = new ConcurrentHashMap<>();

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean paused   = new AtomicBoolean(false);
    private final AtomicInteger density  = new AtomicInteger(DENSITY_MEDIUM);
    private final AtomicInteger vehicleCounter = new AtomicInteger(0);

    private ScheduledExecutorService executor;
    private final Random rng = new Random();

    // ── Vehicle types and their profile defaults ──────────────────────────────
    private enum VType { SEDAN, SUV, TRUCK, AMBULANCE }

    // Slower speeds so motion is visually readable on screen (~50–90 km/h range)
    private static final Map<VType, double[]> SPEED_RANGES = Map.of(
        VType.SEDAN,     new double[]{12, 20},   // 43-72 km/h
        VType.SUV,       new double[]{11, 18},   // 40-65 km/h
        VType.TRUCK,     new double[]{7,  13},   // 25-47 km/h (heavy, slow)
        VType.AMBULANCE, new double[]{18, 28}    // 65-101 km/h (with siren)
    );

    // ── Inner physics context ─────────────────────────────────────────────────
    private static class VehiclePhysics {
        VType type;
        double desiredSpeed;     // cruise m/s
        double maxAccel;         // m/s²
        int targetLane;          // desired lane for lane-change logic
        double laneChangeProgress; // 0→1 during a lane change animation
        boolean changingLane;
        int laneChangeCooldownTicks;
        boolean breakdownTriggered;
        boolean pullingOver;     // moving to shoulder
        double breakdownX;       // where it finally stops
        int hazardBlinkTick;
        boolean yieldingForEmergency;
        double mileage;          // virtual km driven
        double wearAccumulator;

        VehiclePhysics(VType type, double desiredSpeed, double maxAccel, int startLane) {
            this.type = type;
            this.desiredSpeed = desiredSpeed;
            this.maxAccel = maxAccel;
            this.targetLane = startLane;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public TrafficSimulationService(UdpListenerService udpListenerService) {
        this.udpListenerService = udpListenerService;
    }

    @PostConstruct
    public void autoStart() {
        start();
    }

    public void start() {
        if (running.get()) return;
        running.set(true);
        paused.set(false);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-loop");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        System.out.println("[SIM] Traffic simulation engine started.");
    }

    public void pause() {
        paused.set(!paused.get());
        System.out.println("[SIM] " + (paused.get() ? "Paused." : "Resumed."));
    }

    public void reset() {
        simVehicles.clear();
        physics.clear();
        vehicleCounter.set(0);
        paused.set(false);
        System.out.println("[SIM] Reset — all simulated vehicles cleared.");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) executor.shutdownNow();
    }

    public void setDensity(String level) {
        switch (level.toUpperCase()) {
            case "LIGHT"  -> density.set(DENSITY_LIGHT);
            case "RUSH"   -> density.set(DENSITY_RUSH);
            default       -> density.set(DENSITY_MEDIUM);
        }
    }

    // ── Public controls ───────────────────────────────────────────────────────

    public String spawnVehicle(String typeStr, int lane, double speedMps, double wearPercent) {
        VType type;
        try { type = VType.valueOf(typeStr.toUpperCase()); }
        catch (Exception e) { type = VType.SEDAN; }
        String id = buildId(type, vehicleCounter.incrementAndGet());
        createVehicle(id, type, lane, speedMps, wearPercent, 0.0);
        return id;
    }

    /** Trigger a named scenario. */
    public void triggerScenario(String name) {
        switch (name.toLowerCase()) {
            case "emergency_brake"  -> scenarioEmergencyBrake();
            case "ambulance"        -> scenarioAmbulance();
            case "overheat"         -> scenarioOverheat();
            case "overtake"         -> scenarioOvertake();
            case "convoy"           -> scenarioConvoy();
            default                 -> System.out.println("[SIM] Unknown scenario: " + name);
        }
    }

    /** Send an arbitrary RSU command to a simulated vehicle (mirrors WebSocket/UDP path). */
    public void applyCommand(String vehicleId, String field, double value) {
        VehicleState vs = simVehicles.get(vehicleId);
        VehiclePhysics ph = physics.get(vehicleId);
        if (vs == null || ph == null) return;

        switch (field.toUpperCase()) {
            case "SPEED"     -> { ph.desiredSpeed = value; vs.setTargetSpeedMps(value); }
            case "POSITION"  -> vs.setX(value);
            case "BREAKDOWN" -> triggerBreakdown(vehicleId);
            case "LANE"      -> { int l = (int) value; if (l >= 0 && l <= 3) ph.targetLane = l; }
        }
    }

    public void triggerBreakdown(String vehicleId) {
        VehiclePhysics ph = physics.get(vehicleId);
        if (ph != null) {
            ph.breakdownTriggered = true;
            ph.pullingOver = true;
        }
    }

    /** Merge simulated vehicles into the shared UDP map so the rest of the pipeline sees them. */
    public void mergeIntoSharedMap() {
        ConcurrentHashMap<String, VehicleState> shared = udpListenerService.getVehicles();
        // Add/update simulated vehicles
        simVehicles.forEach(shared::put);
        // Remove simulated vehicles that have been despawned
        shared.keySet().removeIf(id -> id.startsWith("SIM-") && !simVehicles.containsKey(id));
    }

    public boolean isRunning() { return running.get(); }
    public boolean isPaused()  { return paused.get();  }
    public int getActiveDensity() { return density.get(); }

    // ── Main simulation tick ─────────────────────────────────────────────────

    private void tick() {
        if (!running.get() || paused.get()) return;

        try {
            ensureTrafficDensity();
            List<String> toRemove = new ArrayList<>();

            for (Map.Entry<String, VehicleState> entry : simVehicles.entrySet()) {
                String id = entry.getKey();
                VehicleState vs = entry.getValue();
                VehiclePhysics ph = physics.get(id);
                if (ph == null) continue;

                if (vs.getX() > DESPAWN_X) { toRemove.add(id); continue; }

                updatePhysics(vs, ph);
                updateSensors(vs, ph);
                updateWear(vs, ph);
                updateYieldForEmergency(vs, ph);
                vs.setTimestampMillis(System.currentTimeMillis());
            }

            toRemove.forEach(id -> { simVehicles.remove(id); physics.remove(id); });
            mergeIntoSharedMap();

        } catch (Exception e) {
            System.err.println("[SIM] Tick error: " + e.getMessage());
        }
    }

    // ── Physics update ────────────────────────────────────────────────────────

    private void updatePhysics(VehicleState vs, VehiclePhysics ph) {
        if ("BREAKDOWN".equals(vs.getStatus())) {
            // Stopped on shoulder — no movement
            vs.setSpeedMps(0);
            vs.setBrakeLights(false);
            ph.hazardBlinkTick++;
            vs.setTurnSignal(ph.hazardBlinkTick % 20 < 10 ? "HAZARD" : "NONE");
            return;
        }

        if (ph.pullingOver) {
            pullOverUpdate(vs, ph);
            return;
        }

        // ─ Lane change logic ─
        if (!ph.changingLane && ph.laneChangeCooldownTicks > 0) ph.laneChangeCooldownTicks--;

        if (!ph.changingLane && ph.laneChangeCooldownTicks == 0) {
            considerLaneChange(vs, ph);
        }

        if (ph.changingLane) {
            ph.laneChangeProgress = Math.min(1.0, ph.laneChangeProgress + DT / 2.0);
            if (ph.laneChangeProgress >= 1.0) {
                vs.setLane(ph.targetLane);
                ph.changingLane = false;
                ph.laneChangeProgress = 0;
                ph.laneChangeCooldownTicks = 60; // ~3s cooldown
                vs.setTurnSignal("NONE");
            }
        }

        // ─ IDM acceleration ─
        double accel = idmAccel(vs, ph);

        // Ambulance always pushes through
        if (ph.type == VType.AMBULANCE && vs.isSirenActive()) {
            accel = Math.max(accel, ph.maxAccel * 0.7);
        }

        double newSpeed = Math.max(0, vs.getSpeedMps() + accel * DT);
        newSpeed = Math.min(ph.desiredSpeed * 1.3, newSpeed);

        vs.setSpeedMps(newSpeed);
        vs.setAccelerationMps2(accel);
        vs.setBrakeLights(accel < -1.5);
        vs.setX(vs.getX() + newSpeed * DT);
        ph.mileage += (newSpeed * DT) / 1000.0;
    }

    private double idmAccel(VehicleState self, VehiclePhysics ph) {
        // Find the closest vehicle ahead in the same lane (or target lane during change)
        int checkLane = ph.changingLane ? ph.targetLane : self.getLane();
        VehicleState leader = null;
        double minGap = Double.MAX_VALUE;

        for (VehicleState other : simVehicles.values()) {
            if (other.getId().equals(self.getId())) continue;
            if (other.getLane() != checkLane) continue;
            double gap = other.getX() - self.getX();
            if (gap > 0 && gap < minGap) { minGap = gap; leader = other; }
        }
        // Also check UDP vehicles in same lane
        for (VehicleState other : udpListenerService.getVehicles().values()) {
            if (other.getId().equals(self.getId())) continue;
            if (other.getLane() != checkLane) continue;
            double gap = other.getX() - self.getX();
            if (gap > 0 && gap < minGap) { minGap = gap; leader = other; }
        }

        double v   = self.getSpeedMps();
        double v0  = ph.desiredSpeed;
        double freeRoadAccel = ph.maxAccel * (1 - Math.pow(v / Math.max(v0, 0.1), IDM_DELTA));

        if (leader == null) return freeRoadAccel;

        double deltaV = v - leader.getSpeedMps();
        double sStar = IDM_MIN_GAP + Math.max(0, v * IDM_DESIRED_TIME_GAP + v * deltaV / (2 * Math.sqrt(ph.maxAccel * IDM_COMFORTABLE_DECEL)));
        double interactionTerm = Math.pow(sStar / Math.max(minGap - 4.0, 0.1), 2);
        return ph.maxAccel * (1 - Math.pow(v / Math.max(v0, 0.1), IDM_DELTA) - interactionTerm);
    }

    private void considerLaneChange(VehicleState vs, VehiclePhysics ph) {
        if (vs.getLane() == 3) return; // already on shoulder

        // Ambulance: always try lane 0 (fast lane) when siren is on
        if (ph.type == VType.AMBULANCE && vs.isSirenActive()) {
            if (vs.getLane() != 0) {
                if (isLaneChangeSafe(vs, 0)) {
                    initiateChangeTo(vs, ph, 0);
                }
            }
            return;
        }

        // Is there a slow leader? Maybe overtake to left
        VehicleState leader = getLeaderInLane(vs, vs.getLane());
        if (leader != null) {
            double gap = leader.getX() - vs.getX();
            if (gap < 30 && leader.getSpeedMps() < vs.getSpeedMps() * 0.85) {
                int overtakeLane = vs.getLane() - 1; // left
                if (overtakeLane >= 0 && isLaneChangeSafe(vs, overtakeLane)) {
                    initiateChangeTo(vs, ph, overtakeLane);
                    return;
                }
            }
        }

        // Mild tendency: trucks & SUVs drift right when free
        if ((ph.type == VType.TRUCK || ph.type == VType.SUV) && vs.getLane() < 2) {
            if (leader == null || (leader.getX() - vs.getX()) > 80) {
                if (rng.nextInt(200) == 0 && isLaneChangeSafe(vs, vs.getLane() + 1)) {
                    initiateChangeTo(vs, ph, vs.getLane() + 1);
                }
            }
        }
    }

    private void initiateChangeTo(VehicleState vs, VehiclePhysics ph, int newLane) {
        ph.targetLane = newLane;
        ph.changingLane = true;
        ph.laneChangeProgress = 0;
        vs.setTurnSignal(newLane < vs.getLane() ? "LEFT" : "RIGHT");
    }

    private boolean isLaneChangeSafe(VehicleState self, int newLane) {
        if (newLane < 0 || newLane > 2) return false;
        for (VehicleState other : simVehicles.values()) {
            if (other.getId().equals(self.getId())) continue;
            if (other.getLane() != newLane) continue;
            double gap = Math.abs(other.getX() - self.getX());
            if (gap < 20) return false;
        }
        return true;
    }

    private VehicleState getLeaderInLane(VehicleState self, int lane) {
        VehicleState leader = null;
        double minGap = 60.0;
        for (VehicleState other : simVehicles.values()) {
            if (other.getId().equals(self.getId())) continue;
            if (other.getLane() != lane) continue;
            double gap = other.getX() - self.getX();
            if (gap > 0 && gap < minGap) { minGap = gap; leader = other; }
        }
        return leader;
    }

    private void pullOverUpdate(VehicleState vs, VehiclePhysics ph) {
        // Brake first
        double newSpeed = Math.max(0, vs.getSpeedMps() - 4.0 * DT);
        vs.setSpeedMps(newSpeed);
        vs.setBrakeLights(true);
        vs.setTurnSignal("RIGHT");

        if (newSpeed < 5.0 && vs.getLane() < 3) {
            // Shift one lane right towards shoulder
            vs.setLane(Math.min(3, vs.getLane() + 1));
        }
        if (vs.getLane() == 3 && newSpeed < 1.0) {
            vs.setSpeedMps(0);
            vs.setStatus("BREAKDOWN");
            vs.setBrakeLights(false);
            vs.setEmergency(false);
            ph.pullingOver = false;
        }
        vs.setX(vs.getX() + newSpeed * DT);
        ph.mileage += (newSpeed * DT) / 1000.0;
    }

    // ── Emergency vehicle corridor yielding ───────────────────────────────────

    private void updateYieldForEmergency(VehicleState vs, VehiclePhysics ph) {
        if (ph.type == VType.AMBULANCE) return;
        if ("BREAKDOWN".equals(vs.getStatus())) return;

        boolean ambulanceNearby = false;
        for (VehicleState other : simVehicles.values()) {
            VehiclePhysics oph = physics.get(other.getId());
            if (oph == null || oph.type != VType.AMBULANCE) continue;
            if (!other.isSirenActive()) continue;
            double gap = other.getX() - vs.getX();
            if (gap > -50 && gap < 80) { ambulanceNearby = true; break; }
        }

        if (ambulanceNearby && !ph.yieldingForEmergency) {
            ph.yieldingForEmergency = true;
            int yieldLane = Math.min(vs.getLane() + 1, 2);
            if (isLaneChangeSafe(vs, yieldLane)) {
                initiateChangeTo(vs, ph, yieldLane);
            }
        } else if (!ambulanceNearby) {
            ph.yieldingForEmergency = false;
        }
    }

    // ── Sensor simulation ─────────────────────────────────────────────────────

    private void updateSensors(VehicleState vs, VehiclePhysics ph) {
        double speed = vs.getSpeedMps();
        boolean isHeavy = (ph.type == VType.TRUCK);

        // Engine temperature: rises with speed and load, falls slowly at idle
        double targetTemp = 75 + speed * 1.8 + (isHeavy ? 15 : 0) + vs.getEngineWearPct() * 0.3;
        double tempDrift = (targetTemp - vs.getEngineTemp()) * 0.02;
        vs.setEngineTemp(vs.getEngineTemp() + tempDrift + rng.nextGaussian() * 0.3);

        // Vibration: increases with wear and speed
        double baseVib = 0.2 + speed * 0.015 + vs.getBrakeWearPct() * 0.01 + vs.getTyreWearPct() * 0.012;
        vs.setVibration(Math.max(0, baseVib + rng.nextGaussian() * 0.05));

        // Oil pressure: drops with engine wear and temp
        double targetOil = 45 - vs.getEngineWearPct() * 0.25 - (vs.getEngineTemp() - 85) * 0.05;
        vs.setOilPressure(Math.max(5, targetOil + rng.nextGaussian() * 0.5));

        // Cycle count (pseudo-odometer)
        vs.setCycle(vs.getCycle() + speed * DT);
    }

    // ── Wear degradation ──────────────────────────────────────────────────────

    private void updateWear(VehicleState vs, VehiclePhysics ph) {
        ph.wearAccumulator += DT;
        if (ph.wearAccumulator < 5.0) return; // only evaluate every 5s
        ph.wearAccumulator = 0;

        double speed = vs.getSpeedMps();
        double speedFactor = speed / 25.0;
        boolean hardBraking = vs.isBrakeLights() && vs.getAccelerationMps2() < -2.5;

        vs.setEngineWearPct(vs.getEngineWearPct()     + 0.04 * speedFactor + (vs.getEngineTemp() > 100 ? 0.08 : 0));
        vs.setBrakeWearPct(vs.getBrakeWearPct()       + (hardBraking ? 0.15 : 0.02));
        vs.setTyreWearPct(vs.getTyreWearPct()         + 0.03 * speedFactor + (hardBraking ? 0.05 : 0));
        vs.setRadiatorWearPct(vs.getRadiatorWearPct() + 0.015);
        vs.setAcWearPct(vs.getAcWearPct()             + 0.01);
        vs.setTimingBeltWearPct(vs.getTimingBeltWearPct() + 0.008 * speedFactor);

        // Critical wear → auto breakdown
        if (!ph.breakdownTriggered) {
            if (vs.getEngineWearPct() >= 95) { ph.breakdownTriggered = true; ph.pullingOver = true; vs.setReason("ENGINE_FAILURE"); vs.setEmergency(true); }
            else if (vs.getBrakeWearPct() >= 95) { ph.breakdownTriggered = true; ph.pullingOver = true; vs.setReason("BRAKE_FAILURE"); vs.setEmergency(true); }
            else if (vs.getTyreWearPct() >= 95) { ph.breakdownTriggered = true; ph.pullingOver = true; vs.setReason("TYRE_FAILURE"); vs.setEmergency(true); }
        }
    }

    // ── Traffic density management ─────────────────────────────────────────────

    private void ensureTrafficDensity() {
        long activeSim = simVehicles.values().stream()
                .filter(v -> !"BREAKDOWN".equals(v.getStatus()) && v.getLane() != 3)
                .count();

        if (activeSim < density.get()) {
            spawnRandomVehicle();
        }
    }

    private void spawnRandomVehicle() {
        VType type = pickRandomType();
        int lane = (type == VType.TRUCK) ? 2 : rng.nextInt(NUM_TRAFFIC_LANES);
        double[] speedRange = SPEED_RANGES.get(type);
        double speed = speedRange[0] + rng.nextDouble() * (speedRange[1] - speedRange[0]);
        double wear = rng.nextDouble() * 35; // 0–35% initial wear
        int num = vehicleCounter.incrementAndGet();
        String id = buildId(type, num);

        // For the first few vehicles: spread across 0–400m so the road looks naturally populated.
        // For subsequent vehicles: enter from BEHIND the pack so we see natural catch-up motion.
        double spawnX;
        int currentCount = simVehicles.size();
        if (currentCount < 4) {
            // Stagger initial vehicles: every ~80-120m apart
            spawnX = currentCount * (80 + rng.nextDouble() * 40);
        } else {
            // Find trailing vehicle position and spawn 40-100m behind it
            double trailingX = simVehicles.values().stream()
                    .filter(v -> !"BREAKDOWN".equals(v.getStatus()) && v.getLane() != 3)
                    .mapToDouble(VehicleState::getX)
                    .min()
                    .orElse(0.0);
            spawnX = Math.max(0, trailingX - 40 - rng.nextDouble() * 60);
        }

        createVehicle(id, type, lane, speed, wear, spawnX);
    }

    private VType pickRandomType() {
        int r = rng.nextInt(100);
        if (r < 55) return VType.SEDAN;
        if (r < 75) return VType.SUV;
        if (r < 90) return VType.TRUCK;
        return VType.AMBULANCE;
    }

    private String buildId(VType type, int num) {
        String prefix = switch (type) {
            case SEDAN     -> "SIM-CAR";
            case SUV       -> "SIM-SUV";
            case TRUCK     -> "SIM-TRK";
            case AMBULANCE -> "SIM-AMB";
        };
        return prefix + "-" + String.format("%02d", num);
    }

    private void createVehicle(String id, VType type, int lane, double speed, double wear, double startX) {
        VehicleState vs = new VehicleState();
        vs.setStatus("NORMAL");
        vs.setReason("");
        vs.setId_internal(id); // we use a helper because VehicleState has no id setter
        vs.setX(startX);
        vs.setLane(lane);
        vs.setSpeedMps(speed);
        vs.setTargetSpeedMps(speed);
        vs.setTimestampMillis(System.currentTimeMillis());
        vs.setVehicleType(type.name());

        // Spread wear across components
        vs.setEngineWearPct(wear * (0.8 + rng.nextDouble() * 0.4));
        vs.setBrakeWearPct(wear * (0.7 + rng.nextDouble() * 0.5));
        vs.setTyreWearPct(wear * (0.9 + rng.nextDouble() * 0.3));
        vs.setRadiatorWearPct(wear * (0.5 + rng.nextDouble() * 0.5));
        vs.setAcWearPct(wear * (0.4 + rng.nextDouble() * 0.5));
        vs.setTimingBeltWearPct(wear * (0.6 + rng.nextDouble() * 0.4));

        // Initial sensor values
        vs.setEngineTemp(78 + rng.nextGaussian() * 4);
        vs.setVibration(0.3 + rng.nextDouble() * 0.2);
        vs.setOilPressure(42 + rng.nextGaussian() * 2);
        vs.setCycle(rng.nextInt(50000));

        // Ambulance: siren on by default
        if (type == VType.AMBULANCE) {
            vs.setSirenActive(true);
            vs.setEmergency(true);
        }

        double[] speedRange = SPEED_RANGES.get(type);
        double maxAccel = (type == VType.TRUCK) ? 1.2 : (type == VType.AMBULANCE ? 3.5 : 2.2);

        VehiclePhysics ph = new VehiclePhysics(type, speed, maxAccel, lane);
        ph.desiredSpeed = speedRange[0] + rng.nextDouble() * (speedRange[1] - speedRange[0]);

        simVehicles.put(id, vs);
        physics.put(id, ph);
        System.out.println("[SIM] Spawned " + type + " → " + id + " lane=" + lane + " speed=" + String.format("%.1f", speed) + " m/s");
    }


    // ── Scenarios ─────────────────────────────────────────────────────────────

    private void scenarioEmergencyBrake() {
        // Force lead vehicle to brake hard → wave propagates backwards
        simVehicles.values().stream()
                .filter(v -> !"BREAKDOWN".equals(v.getStatus()))
                .max(Comparator.comparingDouble(VehicleState::getX))
                .ifPresent(v -> {
                    VehiclePhysics ph = physics.get(v.getId());
                    if (ph != null) ph.desiredSpeed = 0;
                    v.setEmergency(true);
                    System.out.println("[SIM] Scenario: Emergency brake triggered on " + v.getId());
                    // Auto-restore after 8s
                    executor.schedule(() -> {
                        if (ph != null) ph.desiredSpeed = SPEED_RANGES.get(ph.type)[1];
                        v.setEmergency(false);
                    }, 8, TimeUnit.SECONDS);
                });
    }

    private void scenarioAmbulance() {
        // Spawn a fast ambulance at the back
        String id = "SIM-AMB-" + String.format("%02d", vehicleCounter.incrementAndGet());
        createVehicle(id, VType.AMBULANCE, 1, 35, 5, 0.0);
        System.out.println("[SIM] Scenario: Ambulance dispatched → " + id);
    }

    private void scenarioOverheat() {
        // Find a random non-breakdown vehicle and give it near-critical engine wear
        simVehicles.values().stream()
                .filter(v -> !"BREAKDOWN".equals(v.getStatus()))
                .findFirst()
                .ifPresent(v -> {
                    v.setEngineWearPct(88);
                    v.setEngineTemp(115);
                    System.out.println("[SIM] Scenario: Overheat triggered on " + v.getId());
                });
    }

    private void scenarioOvertake() {
        // Spawn a fast sedan behind the pack
        String id = "SIM-CAR-" + String.format("%02d", vehicleCounter.incrementAndGet());
        createVehicle(id, VType.SEDAN, 2, 34, 10, 0.0);
        VehiclePhysics ph = physics.get(id);
        if (ph != null) { ph.desiredSpeed = 34; ph.maxAccel = 3.0; }
        System.out.println("[SIM] Scenario: High-speed overtaker spawned → " + id);
    }

    private void scenarioConvoy() {
        // Spawn 3 trucks back-to-back in lane 2
        for (int i = 0; i < 3; i++) {
            String id = "SIM-TRK-" + String.format("%02d", vehicleCounter.incrementAndGet());
            createVehicle(id, VType.TRUCK, 2, 18, 20, -i * 25.0);
        }
        System.out.println("[SIM] Scenario: Heavy convoy spawned (3 trucks)");
    }
}
