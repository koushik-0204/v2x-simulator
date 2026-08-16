package com.v2x.rsu;

import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

@Service
public class CommandSenderService {

    private static final int COMMAND_PORT = 5001;

    public void sendCommand(String vehicleId, String field, double value) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);

            String message = "CMD," + vehicleId + "," + field + "," + value;
            byte[] data = message.getBytes();

            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, COMMAND_PORT);

            socket.send(packet);
            socket.close();

            System.out.println("Relayed command: " + message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
