# V2X Communication Simulator

A simulated **Vehicle-to-Everything (V2X)** communication system modelling how connected vehicles exchange safety-critical data in real time — inspired by real-world standards like **DSRC / C-V2X** — built from first principles to learn networking and backend systems without leaning on pre-built frameworks.

---

## What Is This?

Modern connected vehicles broadcast short **Basic Safety Messages (BSMs)** roughly ten times per second — position, speed, heading, and health telemetry — to every Road-Side Unit (RSU) and vehicle nearby. The RSU aggregates those messages, detects hazards, and pushes warnings back to the mesh in milliseconds.

This project simulates that entire loop:

```
VehicleNode (standalone Java process)
      │  UDP broadcast on port 5000  (BSM wire format)
      ▼
RSU Spring Boot Service  ←→  WebSocket  ←→  Live Dashboard
      │
      ├─ TrafficSimulationService   (IDM physics engine)
      ├─ PredictiveMaintenanceService  (RUL / health scoring)
      ├─ VehicleBroadcastScheduler  (200 ms WebSocket push)
      └─ CommandSenderService  (UDP command relay → port 5001)
```

---

## Architecture

### RSU Service (`src/main/java/com/v2x/rsu/`)

| Class | Role |
|---|---|
| [`UdpListenerService`](src/main/java/com/v2x/rsu/UdpListenerService.java) | Binds UDP port 5000 on a daemon thread; parses incoming BSMs into `VehicleState` and inserts them into a thread-safe `ConcurrentHashMap` |
| [`TrafficSimulationService`](src/main/java/com/v2x/rsu/TrafficSimulationService.java) | Autonomous 20 Hz physics engine — spawns vehicles, drives them with the **Intelligent Driver Model (IDM)**, handles lane changes, emergency-vehicle corridor yielding, wear degradation, and shoulder pull-over on breakdown |
| [`PredictiveMaintenanceService`](src/main/java/com/v2x/rsu/PredictiveMaintenanceService.java) | Calls an external ML microservice every 3 s; falls back to a rule-based **Remaining Useful Life (RUL)** calculator using engine temp, vibration, oil pressure, and six component wear percentages |
| [`VehicleBroadcastScheduler`](src/main/java/com/v2x/rsu/VehicleBroadcastScheduler.java) | Runs at 200 ms; computes pairwise **Time-to-Collision (TTC)** alerts and pushes a JSON payload (vehicles + alerts + maintenance) to all connected WebSocket clients |
| [`VehicleWebSocketHandler`](src/main/java/com/v2x/rsu/VehicleWebSocketHandler.java) | Accepts dashboard connections; routes inbound JSON commands to the simulation engine or relays them via UDP to external VehicleNodes |
| [`CommandSenderService`](src/main/java/com/v2x/rsu/CommandSenderService.java) | Broadcasts RSU commands (`CMD,<id>,<field>,<value>`) as UDP datagrams on port 5001 |
| [`VehicleController`](src/main/java/com/v2x/rsu/VehicleController.java) | REST API — vehicle state, simulation controls, scenario triggers, density control |
| [`VehicleState`](src/main/java/com/v2x/rsu/VehicleState.java) | Rich vehicle POJO: position, lane, speed, six wear metrics, sensor readings, turn signals, brake lights, siren |

### Vehicle Node (`src/VehicleNode.java`)

A **self-contained Java process** that simulates a single physical vehicle on the road. Run one instance per vehicle. Each node:

- Maintains its own physics loop (100 ms tick)
- Listens for neighbour BSMs on port 5000 and builds a local neighbour map
- Applies the **DSRC-style adaptive broadcast interval** (500–1 500 ms, backing off as neighbour count grows)
- Runs a TTC-based collision avoidance algorithm — lane change if safe, else graduated hard braking
- Detects component wear thresholds and autonomously triggers a breakdown + shoulder pull-over
- Listens for RSU commands on port 5001 (`SPEED`, `POSITION`, `LANE`, `BREAKDOWN`)

---

## BSM Wire Format

Both the simulation engine and `VehicleNode` use the same CSV wire format over UDP:

```
id,x,lane,speedMps,timestampMillis,emergency,
engineTemp,vibration,oilPressure,cycle,
engineWearPct,brakeWearPct,tyreWearPct,radiatorWearPct,acWearPct,timingBeltWearPct,
status,reason
```

**Example:**
```
SIM-CAR-01,342.5,1,18.3,1727507234000,0,
82.4,0.31,44.1,1250.0,
12.3,8.7,15.1,6.2,4.8,9.0,
NORMAL,
```

RSU command format (port 5001):
```
CMD,<vehicleId>,<field>,<value>
CMD,SIM-CAR-01,SPEED,15.0
```

---

## Simulation Features

### Physics — Intelligent Driver Model (IDM)
Each simulated vehicle follows the IDM, the de-facto standard microscopic traffic model:

- Desired speed, minimum gap, comfortable deceleration, and acceleration exponent are all configurable
- Vehicles find their closest leader per lane and compute an interaction acceleration term
- Ambulances override IDM and always push through at maximum acceleration when the siren is active

### Vehicle Types

| Type | Speed Range | Behaviour |
|---|---|---|
| `SEDAN` | 43–72 km/h | General traffic, overtakes when blocked |
| `SUV` | 40–65 km/h | Drifts right when road is clear |
| `TRUCK` | 25–47 km/h | Stays in right lane, slow acceleration |
| `AMBULANCE` | 65–101 km/h | Siren on, forces corridor, triggers yield in nearby vehicles |

### Wear Degradation
Six independent wear components accumulate over distance and driving style:

| Component | Accelerated by |
|---|---|
| Engine | High speed, overheating (temp > 100 °C) |
| Brakes | Hard braking events |
| Tyres | Distance driven + hard braking |
| Radiator | Time (gradual) |
| A/C | Time (gradual) |
| Timing Belt | Distance at speed |

When any component crosses **95% wear**, the vehicle enters breakdown mode, activates hazard lights, pulls to the shoulder, and stops.

### Predictive Maintenance
`PredictiveMaintenanceService` evaluates health every 3 s:

1. **ML path** — POST to `http://localhost:8001/predict` with sensor readings; expects `{ vehicleId, predictedRul, healthStatus }`
2. **Fallback** — rule-based RUL formula using the worst-case wear + sensor penalty scoring

| Health Status | Max Wear | RUL Estimate |
|---|---|---|
| `HEALTHY` | < 40% | ~110 hours |
| `MONITOR` | 40–65% | ~25 hours |
| `SERVICE_SOON` | 65–80% | ~6 hours |
| `CRITICAL` | > 80% | < 3 hours |

### Safety Alerts (TTC)
`VehicleBroadcastScheduler` computes pairwise **Time-to-Collision** for every vehicle pair sharing a lane:

```
TTC = gap / closing_speed
```

If TTC < 4 s, an alert is included in the next WebSocket push to all dashboards.

---

## Triggerable Scenarios (REST / WebSocket)

| Scenario | Effect |
|---|---|
| `emergency_brake` | Forces lead vehicle to slam on brakes; deceleration wave propagates backward |
| `ambulance` | Spawns a fast ambulance at the back; traffic yields a corridor |
| `overheat` | Sets a vehicle's engine wear to 88% and temp to 115 °C; breakdown imminent |
| `overtake` | Spawns a high-speed sedan behind the pack; forces lane changes |
| `convoy` | Spawns three trucks back-to-back in lane 2 |

---

## API Reference

### REST Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/vehicles` | All live vehicle states (sim + external nodes) |
| `POST` | `/api/simulation/start` | Start/resume simulation |
| `POST` | `/api/simulation/pause` | Pause or resume simulation |
| `POST` | `/api/simulation/reset` | Clear all simulated vehicles |
| `POST` | `/api/simulation/spawn` | Spawn a vehicle `{ type, lane, speed, wear }` |
| `POST` | `/api/simulation/scenario/{name}` | Trigger a named scenario |
| `POST` | `/api/simulation/density/{level}` | Set traffic density: `LIGHT`, `MEDIUM`, `RUSH` |
| `GET` | `/api/simulation/status` | Simulation running/paused/density/count |

### WebSocket (`ws://localhost:8080/ws/vehicles`)

**Server → Client** (every 200 ms):
```json
{
  "vehicles": [ { "id": "SIM-CAR-01", "x": 342.5, "lane": 1, "speedMps": 18.3, ... } ],
  "alerts":   [ { "from": "SIM-CAR-01", "to": "SIM-TRK-02", "ttc": 2.1, "gap": 38.2 } ],
  "maintenance": { "SIM-CAR-01": { "healthStatus": "MONITOR", "predictedRul": 28.5 } }
}
```

**Client → Server** (commands):
```json
{ "action": "scenario", "name": "ambulance" }
{ "action": "spawn",    "type": "SEDAN", "lane": 1, "speed": 24, "wear": 10 }
{ "action": "density",  "level": "RUSH" }
{ "vehicleId": "SIM-CAR-01", "field": "SPEED", "value": 15.0 }
{ "vehicleId": "SIM-CAR-01", "field": "BREAKDOWN", "value": 1 }
```

---

## Getting Started

### Prerequisites
- Java 21+
- Maven (or use the included `mvnw` wrapper)

### Run the RSU service

```bash
./mvnw spring-boot:run
```

The simulation starts automatically with medium traffic density (~6 vehicles).

### Run an external VehicleNode

Compile and launch one process per vehicle:

```bash
javac src/VehicleNode.java -d out/
java -cp out VehicleNode <id> <startX> <speedMps> <lane> [brakeAfterSeconds] [brakeSpeed]

# Examples
java -cp out VehicleNode V-001 0 22 1
java -cp out VehicleNode V-002 150 18 0 5.0 8.0   # brakes to 8 m/s after 5 s
```

Multiple nodes broadcast simultaneously; the RSU merges them with the simulated fleet.

### Open the dashboard

Navigate to `http://localhost:8080` after starting the RSU service.

---

## Project Structure

```
v2x-simulator/
├── src/
│   ├── VehicleNode.java                        ← Standalone vehicle process
│   └── main/java/com/v2x/rsu/
│       ├── RsuApplication.java
│       ├── UdpListenerService.java             ← UDP receiver (port 5000)
│       ├── TrafficSimulationService.java       ← IDM physics engine
│       ├── PredictiveMaintenanceService.java   ← RUL / health scoring
│       ├── VehicleBroadcastScheduler.java      ← WebSocket push + TTC alerts
│       ├── VehicleWebSocketHandler.java        ← WS command handler
│       ├── CommandSenderService.java           ← UDP command relay (port 5001)
│       ├── VehicleController.java              ← REST API
│       ├── VehicleState.java                   ← Vehicle data model
│       └── WebSocketConfig.java
├── pom.xml                                     ← Spring Boot 3.3, Java 21
└── README.md
```

---

## Key Design Decisions

**Why raw UDP instead of a message broker?**  
DSRC/C-V2X uses raw radio packets. Building the listener by hand over `DatagramSocket` makes the networking concepts explicit — buffer sizes, `setReuseAddress`, blocking I/O, adaptive broadcast intervals — without hiding them behind Kafka or AMQP abstractions.

**Why `ConcurrentHashMap` instead of a database?**  
The vehicle registry is a pure in-memory view of current road state. The map is written by the UDP thread and read by the WebSocket push thread and REST controllers simultaneously. `ConcurrentHashMap` gives lock-free reads and segment-level write locks with no serialisation overhead. Persistence (for history, replay, and ML training data) is planned for a future phase.

**Why IDM?**  
The Intelligent Driver Model is a single closed-form equation that produces realistic acceleration, following, and braking behaviour with only five parameters. It is used in production traffic simulators (SUMO, VISSIM). Implementing it from scratch instead of importing a library makes the traffic dynamics fully inspectable and adjustable.

**Why rule-based maintenance fallback?**  
The external ML microservice might be down. The fallback keeps the health scores meaningful rather than going blank, and it makes the threshold logic auditable without a model serving environment.

---

## Roadmap

- [ ] Persist vehicle telemetry history to TimescaleDB for replay and ML training
- [ ] Train and serve a real LSTM model for RUL prediction
- [ ] Add lat/lon coordinates and Haversine proximity alerts
- [ ] Implement SPAT (Signal Phase & Timing) messages for intersection simulation
- [ ] Docker Compose setup for multi-node deployment
- [ ] Grafana dashboard over the telemetry time series

---

## Inspiration & References

- SAE J2735 — DSRC Message Set Dictionary  
- IEEE 1609 — WAVE (Wireless Access in Vehicular Environments)  
- Treiber, M. et al. — *Congestion Dynamics on Motorways* (IDM paper)  
- OpenC2X — Open-source C-V2X platform
