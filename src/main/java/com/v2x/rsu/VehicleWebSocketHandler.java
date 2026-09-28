package com.v2x.rsu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class VehicleWebSocketHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final CommandSenderService commandSenderService;
    private final TrafficSimulationService simulationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VehicleWebSocketHandler(CommandSenderService commandSenderService,
                                   TrafficSimulationService simulationService) {
        this.commandSenderService = commandSenderService;
        this.simulationService = simulationService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("Dashboard connected: " + session.getId() + " (total: " + sessions.size() + ")");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("Dashboard disconnected: " + session.getId() + " (total: " + sessions.size() + ")");
    }

    /**
     * Handles commands from the dashboard WebSocket:
     *
     * Vehicle commands:
     *   {"vehicleId":"SIM-CAR-01","field":"SPEED","value":20}
     *   {"vehicleId":"SIM-CAR-01","field":"BREAKDOWN","value":1}
     *
     * Simulation controls:
     *   {"action":"pause"}
     *   {"action":"reset"}
     *   {"action":"scenario","name":"ambulance"}
     *   {"action":"spawn","type":"SEDAN","lane":1,"speed":24,"wear":10}
     *   {"action":"density","level":"RUSH"}
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());

            // Simulation control actions
            if (node.has("action")) {
                String action = node.get("action").asText();
                switch (action) {
                    case "pause"    -> simulationService.pause();
                    case "reset"    -> simulationService.reset();
                    case "start"    -> simulationService.start();
                    case "scenario" -> simulationService.triggerScenario(node.path("name").asText(""));
                    case "density"  -> simulationService.setDensity(node.path("level").asText("MEDIUM"));
                    case "spawn"    -> {
                        String type  = node.path("type").asText("SEDAN");
                        int    lane  = node.path("lane").asInt(1);
                        double speed = node.path("speed").asDouble(22.0);
                        double wear  = node.path("wear").asDouble(0.0);
                        simulationService.spawnVehicle(type, lane, speed, wear);
                    }
                    case "breakdown" -> {
                        String vid = node.path("vehicleId").asText();
                        if (!vid.isEmpty()) simulationService.triggerBreakdown(vid);
                    }
                    default -> System.out.println("[WS] Unknown action: " + action);
                }
                return;
            }

            // Per-vehicle field command
            if (node.has("vehicleId")) {
                String vehicleId = node.get("vehicleId").asText();
                String field     = node.get("field").asText();
                double value     = node.get("value").asDouble();

                // Route to simulation engine first (for simulated vehicles)
                simulationService.applyCommand(vehicleId, field, value);

                // Also broadcast via UDP for external VehicleNode clients
                commandSenderService.sendCommand(vehicleId, field, value);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
