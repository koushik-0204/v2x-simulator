package com.v2x.rsu;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
public class VehicleController {

    private final UdpListenerService udpListenerService;

    // Spring automatically injects the one UdpListenerService bean here.
    public VehicleController(UdpListenerService udpListenerService) {
        this.udpListenerService = udpListenerService;
    }

    @GetMapping("/api/vehicles")
    public Collection<VehicleState> getVehicles() {
        return udpListenerService.getVehicles().values();
    }
}
