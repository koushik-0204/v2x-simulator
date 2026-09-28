package com.v2x.rsu;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
public class VehicleController {

    private final UdpListenerService udpListenerService;
    private final TrafficSimulationService simulationService;
    private final CommandSenderService commandSenderService;

    public VehicleController(UdpListenerService udpListenerService,
                             TrafficSimulationService simulationService,
                             CommandSenderService commandSenderService) {
        this.udpListenerService = udpListenerService;
        this.simulationService = simulationService;
        this.commandSenderService = commandSenderService;
    }

    @PostMapping("/api/vehicles/command")
    public ResponseEntity<Map<String, String>> sendCommand(@RequestBody Map<String, Object> body) {
        String vehicleId = (String) body.get("vehicleId");
        String field     = (String) body.get("field");
        double value     = ((Number) body.getOrDefault("value", 0)).doubleValue();
        simulationService.applyCommand(vehicleId, field, value);
        commandSenderService.sendCommand(vehicleId, field, value);
        return ok("Command sent: " + field + "=" + value + " → " + vehicleId);
    }

    // ── Vehicle data ─────────────────────────────────────────────────────────

    @GetMapping("/api/vehicles")
    public Collection<VehicleState> getVehicles() {
        return udpListenerService.getVehicles().values();
    }

    // ── Simulation controls ───────────────────────────────────────────────────

    @PostMapping("/api/simulation/start")
    public ResponseEntity<Map<String, String>> startSim() {
        simulationService.start();
        return ok("Simulation started");
    }

    @PostMapping("/api/simulation/pause")
    public ResponseEntity<Map<String, String>> pauseSim() {
        simulationService.pause();
        return ok(simulationService.isPaused() ? "Simulation paused" : "Simulation resumed");
    }

    @PostMapping("/api/simulation/reset")
    public ResponseEntity<Map<String, String>> resetSim() {
        simulationService.reset();
        return ok("Simulation reset");
    }

    @PostMapping("/api/simulation/spawn")
    public ResponseEntity<Map<String, String>> spawnVehicle(@RequestBody Map<String, Object> body) {
        String type  = (String) body.getOrDefault("type",  "SEDAN");
        int    lane  = ((Number) body.getOrDefault("lane",  1)).intValue();
        double speed = ((Number) body.getOrDefault("speed", 22.0)).doubleValue();
        double wear  = ((Number) body.getOrDefault("wear",  0.0)).doubleValue();
        String id = simulationService.spawnVehicle(type, lane, speed, wear);
        return ok("Spawned " + id);
    }

    @PostMapping("/api/simulation/scenario/{name}")
    public ResponseEntity<Map<String, String>> triggerScenario(@PathVariable String name) {
        simulationService.triggerScenario(name);
        return ok("Scenario '" + name + "' triggered");
    }

    @PostMapping("/api/simulation/density/{level}")
    public ResponseEntity<Map<String, String>> setDensity(@PathVariable String level) {
        simulationService.setDensity(level);
        return ok("Density set to " + level);
    }

    @GetMapping("/api/simulation/status")
    public Map<String, Object> getSimStatus() {
        return Map.of(
                "running", simulationService.isRunning(),
                "paused",  simulationService.isPaused(),
                "density", simulationService.getActiveDensity(),
                "vehicleCount", udpListenerService.getVehicles().size()
        );
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> ok(String message) {
        return ResponseEntity.ok(Map.of("status", "ok", "message", message));
    }
}
