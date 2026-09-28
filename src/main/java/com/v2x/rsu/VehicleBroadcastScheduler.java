package com.v2x.rsu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class VehicleBroadcastScheduler {

    private static final double TTC_WARNING_THRESHOLD_SECONDS = 4.0;

    private final UdpListenerService udpListenerService;
    private final VehicleWebSocketHandler webSocketHandler;
    private final PredictiveMaintenanceService predictiveMaintenanceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VehicleBroadcastScheduler(UdpListenerService udpListenerService,
                                      VehicleWebSocketHandler webSocketHandler,
                                      PredictiveMaintenanceService predictiveMaintenanceService) {
        this.udpListenerService = udpListenerService;
        this.webSocketHandler = webSocketHandler;
        this.predictiveMaintenanceService = predictiveMaintenanceService;
    }

    @Scheduled(fixedRate = 200)
    public void pushVehicleUpdates() {
        try {
            Collection<VehicleState> vehicles = udpListenerService.getVehicles().values();

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("vehicles", objectMapper.valueToTree(vehicles));
            payload.set("alerts", objectMapper.valueToTree(computeAlerts(vehicles)));
            payload.set("maintenance", objectMapper.valueToTree(predictiveMaintenanceService.getLatestResults()));

            webSocketHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<Alert> computeAlerts(Collection<VehicleState> vehicles) {
        List<Alert> alerts = new ArrayList<>();
        List<VehicleState> list = new ArrayList<>(vehicles);

        for (int i = 0; i < list.size(); i++) {
            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;

                VehicleState self = list.get(i);
                VehicleState other = list.get(j);

                if (self.getLane() != other.getLane()) continue;
                if (self.getLane() == 3) continue; // shoulder lane: parked vehicles, not a collision scenario

                double gap = other.getX() - self.getX();
                double closingSpeed = self.getSpeedMps() - other.getSpeedMps();

                if (gap > 0 && closingSpeed > 0) {
                    double ttc = gap / closingSpeed;
                    if (ttc < TTC_WARNING_THRESHOLD_SECONDS) {
                        alerts.add(new Alert(self.getId(), other.getId(), ttc, gap));
                    }
                }
            }
        }
        return alerts;
    }

    static class Alert {
        public final String from;
        public final String to;
        public final double ttc;
        public final double gap;

        Alert(String from, String to, double ttc, double gap) {
            this.from = from;
            this.to = to;
            this.ttc = ttc;
            this.gap = gap;
        }
    }
}
