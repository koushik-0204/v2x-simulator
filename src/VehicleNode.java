import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class VehicleNode {

    static final int PORT = 5000;
    static final int COMMAND_PORT = 5001;
    static final double TTC_WARNING_THRESHOLD_SECONDS = 4.0;
    static final int NUM_LANES = 3;
    static final int SHOULDER_LANE = 3;
    static final double MIN_GAP_AHEAD = 15.0;
    static final double MIN_GAP_BEHIND = 15.0;
    static final double CRITICAL_WEAR_THRESHOLD = 95.0;

    static final int TICK_MS = 100;
    static final double TICK_SECONDS = TICK_MS / 1000.0;
    static final long BASE_INTERVAL_MS = 500;
    static final long MAX_INTERVAL_MS = 1500;
    static final long INTERVAL_STEP_PER_NEIGHBOR_MS = 100;

    static final double EMERGENCY_DECEL_THRESHOLD_MPS_PER_TICK = 0.5;
    static final double MAX_ACCEL_MPS2 = 2.0;
    static final double NORMAL_DECEL_MPS2 = 2.0;
    static final double BREAKDOWN_DECEL_MPS2 = 6.0;

    static final double CYCLE_INCREMENT_PER_TICK = 0.3;
    static final Random random = new Random();
    static double failureCycle;
    static double cycle = 0;

    static final double TYRE_LIFE_DISTANCE_M = 2000.0;
    static final double BRAKE_LIFE_UNITS = 40.0;
    static final double RADIATOR_LIFE_MULTIPLIER = 1.8;
    static final double AC_LIFE_MULTIPLIER = 1.5;
    static final double TIMING_BELT_LIFE_MULTIPLIER = 2.5;

    static double odometerMeters = 0;
    static double brakeWearUnits = 0;

    // Breakdown state: triggered either by a component crossing critical wear,
    // or a manual command, for demoability.
    static volatile boolean breakdownActive = false;
    static volatile String breakdownReason = "";

    static class VehicleState {
        final String id;
        final double x;
        final int lane;
        final double speedMps;
        final long timestampMillis;
        final boolean emergency;
        final double engineTemp;
        final double vibration;
        final double oilPressure;
        final double cycleValue;
        final double engineWearPct;
        final double brakeWearPct;
        final double tyreWearPct;
        final double radiatorWearPct;
        final double acWearPct;
        final double timingBeltWearPct;
        final String status;
        final String reason;

        VehicleState(String id, double x, int lane, double speedMps, long timestampMillis, boolean emergency,
                     double engineTemp, double vibration, double oilPressure, double cycleValue,
                     double engineWearPct, double brakeWearPct, double tyreWearPct,
                     double radiatorWearPct, double acWearPct, double timingBeltWearPct,
                     String status, String reason) {
            this.id = id;
            this.x = x;
            this.lane = lane;
            this.speedMps = speedMps;
            this.timestampMillis = timestampMillis;
            this.emergency = emergency;
            this.engineTemp = engineTemp;
            this.vibration = vibration;
            this.oilPressure = oilPressure;
            this.cycleValue = cycleValue;
            this.engineWearPct = engineWearPct;
            this.brakeWearPct = brakeWearPct;
            this.tyreWearPct = tyreWearPct;
            this.radiatorWearPct = radiatorWearPct;
            this.acWearPct = acWearPct;
            this.timingBeltWearPct = timingBeltWearPct;
            this.status = status;
            this.reason = reason;
        }

        String toMessage() {
            return id + "," + x + "," + lane + "," + speedMps + "," + timestampMillis + "," + (emergency ? "1" : "0")
                    + "," + engineTemp + "," + vibration + "," + oilPressure + "," + cycleValue
                    + "," + engineWearPct + "," + brakeWearPct + "," + tyreWearPct
                    + "," + radiatorWearPct + "," + acWearPct + "," + timingBeltWearPct
                    + "," + status + "," + reason;
        }

        static VehicleState fromMessage(String message) {
            String[] p = message.split(",", -1);
            String id = p[0];
            double x = Double.parseDouble(p[1]);
            int lane = Integer.parseInt(p[2]);
            double speed = Double.parseDouble(p[3]);
            long ts = Long.parseLong(p[4]);
            boolean emergency = p.length > 5 && p[5].equals("1");
            double engineTemp = p.length > 6 ? Double.parseDouble(p[6]) : 0;
            double vibration = p.length > 7 ? Double.parseDouble(p[7]) : 0;
            double oilPressure = p.length > 8 ? Double.parseDouble(p[8]) : 0;
            double cycleValue = p.length > 9 ? Double.parseDouble(p[9]) : 0;
            double engineWearPct = p.length > 10 ? Double.parseDouble(p[10]) : 0;
            double brakeWearPct = p.length > 11 ? Double.parseDouble(p[11]) : 0;
            double tyreWearPct = p.length > 12 ? Double.parseDouble(p[12]) : 0;
            double radiatorWearPct = p.length > 13 ? Double.parseDouble(p[13]) : 0;
            double acWearPct = p.length > 14 ? Double.parseDouble(p[14]) : 0;
            double timingBeltWearPct = p.length > 15 ? Double.parseDouble(p[15]) : 0;
            String status = p.length > 16 ? p[16] : "NORMAL";
            String reason = p.length > 17 ? p[17] : "";
            return new VehicleState(id, x, lane, speed, ts, emergency, engineTemp, vibration, oilPressure, cycleValue,
                    engineWearPct, brakeWearPct, tyreWearPct, radiatorWearPct, acWearPct, timingBeltWearPct,
                    status, reason);
        }
    }

    static final Object stateLock = new Object();
    static volatile VehicleState ownState;
    static volatile double desiredSpeedMps;

    static final ConcurrentHashMap<String, VehicleState> neighbors = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {

        String vehicleId = args[0];
        double startX = Double.parseDouble(args[1]);
        double speedMps = Double.parseDouble(args[2]);
        int lane = Integer.parseInt(args[3]);

        Double brakeAtSeconds = args.length > 4 ? Double.parseDouble(args[4]) : null;
        Double brakeSpeed = args.length > 5 ? Double.parseDouble(args[5]) : null;

        failureCycle = 150 + random.nextDouble() * 150;

        double[] sensors = computeSensors();
        double[] wear = computeWear(0, 0);
        ownState = new VehicleState(vehicleId, startX, lane, speedMps, System.currentTimeMillis(), false,
                sensors[0], sensors[1], sensors[2], cycle,
                wear[0], wear[1], wear[2], wear[3], wear[4], wear[5], "NORMAL", "");
        desiredSpeedMps = speedMps;

        Thread broadcasterThread = new Thread(() -> runBroadcaster(brakeAtSeconds, brakeSpeed));
        Thread listenerThread = new Thread(() -> runListener(vehicleId));
        Thread commandListenerThread = new Thread(() -> runCommandListener(vehicleId));

        broadcasterThread.start();
        listenerThread.start();
        commandListenerThread.start();

        broadcasterThread.join();
        listenerThread.join();
        commandListenerThread.join();
    }

    static double[] computeSensors() {
        double frac = Math.min(1.0, cycle / failureCycle);
        double engineTemp = 80 + 40 * frac + random.nextGaussian() * 2;
        double vibration = 0.5 + 3.0 * Math.pow(frac, 1.5) + random.nextGaussian() * 0.1;
        double oilPressure = 60 - 25 * frac + random.nextGaussian() * 1.5;
        return new double[] { engineTemp, vibration, oilPressure };
    }

    static double[] computeWear(double odometer, double brakeUnits) {
        double engineWearPct = Math.min(100, 100 * cycle / failureCycle);
        double radiatorWearPct = Math.min(100, 100 * cycle / (failureCycle * RADIATOR_LIFE_MULTIPLIER));
        double acWearPct = Math.min(100, 100 * cycle / (failureCycle * AC_LIFE_MULTIPLIER));
        double timingBeltWearPct = Math.min(100, 100 * cycle / (failureCycle * TIMING_BELT_LIFE_MULTIPLIER));
        double tyreWearPct = Math.min(100, 100 * odometer / TYRE_LIFE_DISTANCE_M);
        double brakeWearPct = Math.min(100, 100 * brakeUnits / BRAKE_LIFE_UNITS);
        return new double[] { engineWearPct, brakeWearPct, tyreWearPct, radiatorWearPct, acWearPct, timingBeltWearPct };
    }

    static void runBroadcaster(Double brakeAtSeconds, Double brakeSpeed) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");

            long startTime = System.currentTimeMillis();
            long lastBroadcastTime = 0;
            boolean brakeApplied = false;

            while (true) {
                VehicleState snapshot;

                synchronized (stateLock) {
                    VehicleState current = ownState;
                    double previousSpeed = current.speedMps;
                    double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;

                    if (!brakeApplied && brakeAtSeconds != null && elapsedSeconds >= brakeAtSeconds) {
                        desiredSpeedMps = brakeSpeed;
                        brakeApplied = true;
                    }

                    cycle += CYCLE_INCREMENT_PER_TICK;
                    double[] sensors = computeSensors();
                    double[] wear = computeWear(odometerMeters, brakeWearUnits);

                    // Auto-trigger a breakdown the moment a safety-critical component
                    // crosses the critical threshold, if not already broken down.
                    if (!breakdownActive) {
                        if (wear[0] >= CRITICAL_WEAR_THRESHOLD) {
                            breakdownActive = true;
                            breakdownReason = "ENGINE_FAILURE";
                        } else if (wear[1] >= CRITICAL_WEAR_THRESHOLD) {
                            breakdownActive = true;
                            breakdownReason = "BRAKE_FAILURE";
                        } else if (wear[2] >= CRITICAL_WEAR_THRESHOLD) {
                            breakdownActive = true;
                            breakdownReason = "TYRE_FAILURE";
                        }
                        if (breakdownActive) {
                            System.out.println("[" + current.id + "] !!! BREAKDOWN TRIGGERED: " + breakdownReason
                                    + " -- pulling over to shoulder");
                        }
                    }

                    Decision decision = breakdownActive ? decideBreakdown(current) : decide(current);

                    double newSpeed;
                    if (decision.targetSpeed < previousSpeed) {
                        newSpeed = Math.max(decision.targetSpeed, previousSpeed - decision.decelRate * TICK_SECONDS);
                    } else {
                        newSpeed = Math.min(decision.targetSpeed, previousSpeed + MAX_ACCEL_MPS2 * TICK_SECONDS);
                    }
                    newSpeed = Math.max(0, newSpeed);

                    double newX = current.x + newSpeed * TICK_SECONDS;
                    boolean isEmergencyTick = (previousSpeed - newSpeed) >= EMERGENCY_DECEL_THRESHOLD_MPS_PER_TICK;

                    if (decision.newLane != current.lane) {
                        String reasonSuffix = breakdownActive ? " (breakdown: " + breakdownReason + ")" : " (avoiding threat)";
                        System.out.println("[" + current.id + "] LANE CHANGE: " + current.lane + " -> " + decision.newLane + reasonSuffix);
                    }

                    odometerMeters += newSpeed * TICK_SECONDS;
                    if (newSpeed < previousSpeed && decision.decelRate > 0 && !breakdownActive) {
                        brakeWearUnits += decision.decelRate * TICK_SECONDS;
                    }

                    String status = breakdownActive ? "BREAKDOWN" : (isEmergencyTick ? "EMERGENCY_BRAKING" : "NORMAL");

                    ownState = new VehicleState(current.id, newX, decision.newLane, newSpeed,
                            System.currentTimeMillis(), isEmergencyTick,
                            sensors[0], sensors[1], sensors[2], cycle,
                            wear[0], wear[1], wear[2], wear[3], wear[4], wear[5],
                            status, breakdownActive ? breakdownReason : "");
                    snapshot = ownState;
                }

                long now = System.currentTimeMillis();
                long adaptiveInterval = Math.min(
                        BASE_INTERVAL_MS + (long) neighbors.size() * INTERVAL_STEP_PER_NEIGHBOR_MS,
                        MAX_INTERVAL_MS
                );
                boolean periodicDue = (now - lastBroadcastTime) >= adaptiveInterval;

                if (snapshot.emergency || breakdownActive || periodicDue) {
                    byte[] data = snapshot.toMessage().getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, PORT);
                    socket.send(packet);
                    lastBroadcastTime = now;

                    System.out.println("[" + snapshot.id + "] " + snapshot.status + " broadcast, lane=" + snapshot.lane
                            + ", x=" + String.format("%.1f", snapshot.x) + ", speed=" + String.format("%.1f", snapshot.speedMps)
                            + ", interval=" + adaptiveInterval + "ms, neighbors=" + neighbors.size());
                }

                Thread.sleep(TICK_MS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class Decision {
        final double targetSpeed;
        final double decelRate;
        final int newLane;

        Decision(double targetSpeed, double decelRate, int newLane) {
            this.targetSpeed = targetSpeed;
            this.decelRate = decelRate;
            this.newLane = newLane;
        }
    }

    // Once breaking down: head for the shoulder immediately and come to a full stop.
    // No gap-check needed for leaving the travel lane -- removing yourself from a lane
    // can only help whoever was behind you, never hurt them.
    static Decision decideBreakdown(VehicleState self) {
        return new Decision(0, BREAKDOWN_DECEL_MPS2, SHOULDER_LANE);
    }

    static Decision decide(VehicleState self) {
        VehicleState closestAhead = null;
        double smallestGap = Double.MAX_VALUE;

        for (VehicleState neighbor : neighbors.values()) {
            if (neighbor.lane != self.lane) continue;
            double gap = neighbor.x - self.x;
            if (gap > 0 && gap < smallestGap) {
                smallestGap = gap;
                closestAhead = neighbor;
            }
        }

        if (closestAhead == null) {
            return new Decision(desiredSpeedMps, NORMAL_DECEL_MPS2, self.lane);
        }

        double closingSpeed = self.speedMps - closestAhead.speedMps;
        if (closingSpeed <= 0) {
            return new Decision(desiredSpeedMps, NORMAL_DECEL_MPS2, self.lane);
        }

        double ttc = smallestGap / closingSpeed;
        if (ttc >= TTC_WARNING_THRESHOLD_SECONDS) {
            return new Decision(desiredSpeedMps, NORMAL_DECEL_MPS2, self.lane);
        }

        int[] candidates = { self.lane - 1, self.lane + 1 };
        for (int candidateLane : candidates) {
            if (candidateLane < 0 || candidateLane >= NUM_LANES) continue;
            if (isLaneSafe(self, candidateLane)) {
                return new Decision(desiredSpeedMps, NORMAL_DECEL_MPS2, candidateLane);
            }
        }

        double decelRate;
        if (ttc < 1.5) {
            decelRate = 8.0;
        } else if (ttc < 2.5) {
            decelRate = 5.0;
        } else {
            decelRate = 3.0;
        }

        double cappedSpeed = Math.min(desiredSpeedMps, closestAhead.speedMps);
        return new Decision(cappedSpeed, decelRate, self.lane);
    }

    static boolean isLaneSafe(VehicleState self, int candidateLane) {
        for (VehicleState neighbor : neighbors.values()) {
            if (neighbor.lane != candidateLane) continue;

            double gapAhead = neighbor.x - self.x;
            if (gapAhead >= 0) {
                if (gapAhead < MIN_GAP_AHEAD) return false;
                double closingSpeed = self.speedMps - neighbor.speedMps;
                if (closingSpeed > 0 && (gapAhead / closingSpeed) < TTC_WARNING_THRESHOLD_SECONDS) return false;
            }

            double gapBehind = self.x - neighbor.x;
            if (gapBehind >= 0) {
                if (gapBehind < MIN_GAP_BEHIND) return false;
                double closingSpeed = neighbor.speedMps - self.speedMps;
                if (closingSpeed > 0 && (gapBehind / closingSpeed) < TTC_WARNING_THRESHOLD_SECONDS) return false;
            }
        }
        return true;
    }

    static void runListener(String selfId) {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));

            byte[] buffer = new byte[256];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                VehicleState received = VehicleState.fromMessage(message);

                if (received.id.equals(selfId)) {
                    continue;
                }

                neighbors.put(received.id, received);

                if (received.emergency && received.lane == ownState.lane) {
                    System.out.println("  ** EMERGENCY BRAKING ALERT from " + received.id + " (lane " + received.lane + ") **");
                }

                checkSafety(received);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void runCommandListener(String selfId) {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(COMMAND_PORT));

            byte[] buffer = new byte[256];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                String[] parts = message.split(",");

                if (parts.length != 4 || !parts[0].equals("CMD")) {
                    continue;
                }

                String targetId = parts[1];
                if (!targetId.equals(selfId)) {
                    continue;
                }

                String field = parts[2];
                double value = Double.parseDouble(parts[3]);

                if (field.equals("SPEED")) {
                    desiredSpeedMps = value;
                    System.out.println("[" + selfId + "] COMMAND: desired speed set to " + value + " m/s");
                } else if (field.equals("POSITION")) {
                    synchronized (stateLock) {
                        VehicleState c = ownState;
                        ownState = new VehicleState(c.id, value, c.lane, c.speedMps, System.currentTimeMillis(), false,
                                c.engineTemp, c.vibration, c.oilPressure, c.cycleValue,
                                c.engineWearPct, c.brakeWearPct, c.tyreWearPct, c.radiatorWearPct, c.acWearPct,
                                c.timingBeltWearPct, c.status, c.reason);
                    }
                    System.out.println("[" + selfId + "] COMMAND: position set to " + value + " m");
                } else if (field.equals("LANE")) {
                    synchronized (stateLock) {
                        VehicleState c = ownState;
                        ownState = new VehicleState(c.id, c.x, (int) value, c.speedMps, System.currentTimeMillis(), false,
                                c.engineTemp, c.vibration, c.oilPressure, c.cycleValue,
                                c.engineWearPct, c.brakeWearPct, c.tyreWearPct, c.radiatorWearPct, c.acWearPct,
                                c.timingBeltWearPct, c.status, c.reason);
                    }
                    System.out.println("[" + selfId + "] COMMAND: lane set to " + (int) value);
                } else if (field.equals("BREAKDOWN")) {
                    breakdownActive = true;
                    breakdownReason = "MANUAL_TEST";
                    System.out.println("[" + selfId + "] COMMAND: manual breakdown triggered");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void checkSafety(VehicleState neighbor) {
        VehicleState self = ownState;
        if (neighbor.lane != self.lane) return;

        double gap = neighbor.x - self.x;
        double closingSpeed = self.speedMps - neighbor.speedMps;

        if (gap > 0 && closingSpeed > 0) {
            double ttc = gap / closingSpeed;
            if (ttc < TTC_WARNING_THRESHOLD_SECONDS) {
                System.out.println("  !! WARNING [" + self.id + "]: closing on " + neighbor.id
                        + " -- TTC = " + String.format("%.2f", ttc) + "s, gap = "
                        + String.format("%.1f", gap) + "m");
            }
        }
    }
}