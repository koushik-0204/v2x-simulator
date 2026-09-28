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

    private boolean mlServiceReachable = false;

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
            MaintenanceResult result = null;

            // 1. Try external ML service
            if (mlServiceReachable || latestResults.isEmpty()) {
                try {
                    Map<String, Object> request = Map.of(
                            "vehicleId",   v.getId(),
                            "engineTemp",  v.getEngineTemp(),
                            "vibration",   v.getVibration(),
                            "oilPressure", v.getOilPressure(),
                            "cycle",       v.getCycle()
                    );
                    result = restTemplate.postForObject(MAINTENANCE_SERVICE_URL, request, MaintenanceResult.class);
                    mlServiceReachable = true;
                } catch (Exception e) {
                    mlServiceReachable = false;
                }
            }

            // 2. Fallback: rule-based RUL & health assessment
            if (result == null) {
                result = computeFallbackMaintenance(v);
            }

            if (result != null) {
                latestResults.put(v.getId(), result);
            }
        }
    }

    /**
     * Rule-based maintenance assessment used when the ML microservice is unavailable.
     *
     * Health thresholds (based on worst-case component wear):
     *   < 40% wear  → HEALTHY      RUL = linear estimate to 95% threshold
     *   40–65%      → MONITOR
     *   65–80%      → SERVICE_SOON
     *   > 80%       → CRITICAL
     *
     * RUL formula: estimated hours of operation before the most degraded component hits 95%.
     */
    private MaintenanceResult computeFallbackMaintenance(VehicleState v) {
        double maxWear = Math.max(v.getEngineWearPct(),
                         Math.max(v.getBrakeWearPct(),
                         Math.max(v.getTyreWearPct(),
                         Math.max(v.getRadiatorWearPct(),
                         Math.max(v.getAcWearPct(), v.getTimingBeltWearPct())))));

        // Sensor penalty: abnormal readings add a virtual 5–15% wear offset
        double sensorPenalty = 0;
        if (v.getEngineTemp() > 100) sensorPenalty += (v.getEngineTemp() - 100) * 0.3;
        if (v.getVibration() > 1.0)  sensorPenalty += (v.getVibration() - 1.0) * 5;
        if (v.getOilPressure() < 25) sensorPenalty += (25 - v.getOilPressure()) * 0.4;

        double effectiveWear = Math.min(100, maxWear + sensorPenalty);

        String health;
        double rul;
        if (effectiveWear < 40) {
            health = "HEALTHY";
            rul = (95 - effectiveWear) / 0.5; // ~ hours at typical degradation rate
        } else if (effectiveWear < 65) {
            health = "MONITOR";
            rul = (95 - effectiveWear) / 1.2;
        } else if (effectiveWear < 80) {
            health = "SERVICE_SOON";
            rul = (95 - effectiveWear) / 2.5;
        } else {
            health = "CRITICAL";
            rul = Math.max(0, (95 - effectiveWear) / 5.0);
        }

        MaintenanceResult r = new MaintenanceResult();
        r.vehicleId    = v.getId();
        r.predictedRul = Math.max(0, rul);
        r.healthStatus = health;
        return r;
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