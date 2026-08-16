package com.v2x.rsu;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UdpListenerService {

    private static final int PORT = 5000;

    private final ConcurrentHashMap<String, VehicleState> vehicles = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        Thread listenerThread = new Thread(this::listen);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listen() {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));

            byte[] buffer = new byte[256];

            System.out.println("RSU listening for vehicle broadcasts on port " + PORT);

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                VehicleState state = VehicleState.fromMessage(message);
                vehicles.put(state.getId(), state);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ConcurrentHashMap<String, VehicleState> getVehicles() {
        return vehicles;
    }
}