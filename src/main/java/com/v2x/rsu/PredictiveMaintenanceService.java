package com.v2x.rsu;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PredictiveMaintenanceService {

    private static final String MAINTENANCE_SERVICE_URL = "http://localhost:8001/predict";

    private final UdpListenerService udpListenerService;
    private final RestTemplate restTemplate;

    // Cache of the latest prediction per vehicle. A separate, slower cadence than the
    // 500ms position push -- ML inference doesn't need to run that often, and this
    // insulates the rest of the dashboard from the maintenance service being slow or down.
    private final ConcurrentHashMap<String, MaintenanceResult> latestResults = new ConcurrentHashMap<>();

    public PredictiveMaintenanceService(UdpListenerService udpListenerService, RestTemplateBuilder builder) {
        this.udpListenerService = udpListenerService;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(800))
                .setReadTimeout(Duration.ofMillis(800))
                .build();
    }

    @Scheduled(fixedRate = 3000)
    public void refreshPredictions() {
        Collection<VehicleState> vehicles = udpListenerService.getVehicles().values();

        for (VehicleState v : vehicles) {
            try {
                Map<String, Object> request = Map.of(
                        "vehicleId", v.getId(),
                        "engineTemp", v.getEngineTemp(),
                        "vibration", v.getVibration(),
                        "oilPressure", v.getOilPressure(),
                        "cycle", v.getCycle()
                );

                MaintenanceResult result = restTemplate.postForObject(MAINTENANCE_SERVICE_URL, request, MaintenanceResult.class);
                if (result != null) {
                    latestResults.put(v.getId(), result);
                }
            } catch (Exception e) {
                // Maintenance service being unreachable shouldn't break the rest of the dashboard --
                // just skip this vehicle's update and try again next cycle.
                System.out.println("Maintenance prediction unavailable for " + v.getId() + ": " + e.getMessage());
            }
        }
    }

    public Map<String, MaintenanceResult> getLatestResults() {
        return latestResults;
    }

    public static class MaintenanceResult {
        public String vehicleId;
        public double predictedRul;
        public String healthStatus;
    }
}