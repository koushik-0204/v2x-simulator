package com.v2x.rsu;

public class VehicleState {

    private String id;
    private double x;
    private int lane;
    private double speedMps;
    private long timestampMillis;
    private boolean emergency;
    private double engineTemp;
    private double vibration;
    private double oilPressure;
    private double cycle;
    private double engineWearPct;
    private double brakeWearPct;
    private double tyreWearPct;
    private double radiatorWearPct;
    private double acWearPct;
    private double timingBeltWearPct;
    private String status;
    private String reason;

    public VehicleState() {
    }

    public VehicleState(String id, double x, int lane, double speedMps, long timestampMillis, boolean emergency,
                         double engineTemp, double vibration, double oilPressure, double cycle,
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
        this.cycle = cycle;
        this.engineWearPct = engineWearPct;
        this.brakeWearPct = brakeWearPct;
        this.tyreWearPct = tyreWearPct;
        this.radiatorWearPct = radiatorWearPct;
        this.acWearPct = acWearPct;
        this.timingBeltWearPct = timingBeltWearPct;
        this.status = status;
        this.reason = reason;
    }

    // Wire format from VehicleNode.java:
    // id,x,lane,speed,timestamp,emergency,engineTemp,vibration,oilPressure,cycle,
    // engineWear,brakeWear,tyreWear,radiatorWear,acWear,timingBeltWear,status,reason
    public static VehicleState fromMessage(String message) {
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
        double cycle = p.length > 9 ? Double.parseDouble(p[9]) : 0;
        double engineWearPct = p.length > 10 ? Double.parseDouble(p[10]) : 0;
        double brakeWearPct = p.length > 11 ? Double.parseDouble(p[11]) : 0;
        double tyreWearPct = p.length > 12 ? Double.parseDouble(p[12]) : 0;
        double radiatorWearPct = p.length > 13 ? Double.parseDouble(p[13]) : 0;
        double acWearPct = p.length > 14 ? Double.parseDouble(p[14]) : 0;
        double timingBeltWearPct = p.length > 15 ? Double.parseDouble(p[15]) : 0;
        String status = p.length > 16 ? p[16] : "NORMAL";
        String reason = p.length > 17 ? p[17] : "";
        return new VehicleState(id, x, lane, speed, ts, emergency, engineTemp, vibration, oilPressure, cycle,
                engineWearPct, brakeWearPct, tyreWearPct, radiatorWearPct, acWearPct, timingBeltWearPct,
                status, reason);
    }

    public String getId() { return id; }
    public double getX() { return x; }
    public int getLane() { return lane; }
    public double getSpeedMps() { return speedMps; }
    public long getTimestampMillis() { return timestampMillis; }
    public boolean isEmergency() { return emergency; }
    public double getEngineTemp() { return engineTemp; }
    public double getVibration() { return vibration; }
    public double getOilPressure() { return oilPressure; }
    public double getCycle() { return cycle; }
    public double getEngineWearPct() { return engineWearPct; }
    public double getBrakeWearPct() { return brakeWearPct; }
    public double getTyreWearPct() { return tyreWearPct; }
    public double getRadiatorWearPct() { return radiatorWearPct; }
    public double getAcWearPct() { return acWearPct; }
    public double getTimingBeltWearPct() { return timingBeltWearPct; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
}
