# Smart Rider Safety & Hazard Mitigation System (SRSHMS)

## IoTrix 2.0 – National IoT Innovation Challenge

### Track A – Embedded IoT System Development

---

## 1. Project Overview

SRSHMS is an IoT-based safety system designed to support two-wheeler
riders during roadside hazard situations and after a possible crash.

The system combines nearby-object detection, audio classification,
rider warnings, crash detection and emergency communication.

The main concept is:

**Prevent → Protect → Rescue**

---

## 2. Problem

Two-wheeler riders may suddenly encounter roadside hazards such as
dogs or other moving objects. A rider may have very little time to
react, especially in situations involving unexpected movement.

A second problem occurs after a crash. If the rider is injured or
unable to use a phone, contacting someone for help can become
difficult.

Therefore, our project aims to provide an additional safety layer
that can warn the rider and support emergency response.

---

## 3. Proposed Solution

The proposed system uses an ESP32 as the main controller.

A radar sensor is used to detect nearby objects or movement, while
a microphone and audio classification system are used to analyze
sounds such as dog barking, human voice and vehicle/background sounds.

The sensor information is combined before deciding whether a
possible roadside hazard exists.

The rider receives directional warnings using left/right vibration
motors, LEDs and an OLED display.

The system also includes crash detection. When a possible crash is
detected, a short cancellation period is provided. If the rider
does not cancel the alert, the system can activate a local siren
and use GPS/IoT communication for emergency notification.

---

## 4. Main Features

- Nearby object/movement detection
- Audio-based sound classification
- Possible dog-related hazard detection
- Left/right directional rider warning
- Vibration-based warning
- LED indication
- OLED status display
- Crash detection
- Emergency cancellation period
- Local siren
- GPS location
- IoT/cloud emergency notification
- Local safety operation during network failure

---

## 5. Important Sensor-Fusion Concept

The radar sensor does NOT identify a dog.

Radar is used to detect a nearby physical object or movement.

The audio classification system analyzes the sound and can classify
categories such as:

- Dog Bark
- Human Voice
- Vehicle/Traffic Sound
- Background Noise

The radar information and audio classification result are then
combined to estimate a possible dog-related hazard.

---

## 6. System Architecture

                    SMART RIDER SAFETY SYSTEM
                              │
                              ▼
                  ┌───────────────────────┐
                  │       SENSING         │
                  │                       │
                  │  Radar / Proximity    │
                  │  Microphone Sensor    │
                  │  Crash/IMU Sensor     │
                  │  GPS                  │
                  └───────────┬───────────┘
                              │
                              ▼
                  ┌───────────────────────┐
                  │      ESP32 MCU        │
                  │                       │
                  │ • Sensor Processing   │
                  │ • Filtering            │
                  │ • Hazard Decision     │
                  │ • Alert Control       │
                  │ • Communication       │
                  └───────────┬───────────┘
                              │
                ┌─────────────┼─────────────┐
                │             │             │
                ▼             ▼             ▼
        ┌────────────┐ ┌────────────┐ ┌─────────────┐
        │   Rider    │ │   Visual   │ │ Emergency   │
        │   Alert    │ │   Display  │ │  Response   │
        │            │ │            │ │             │
        │ Vibration  │ │ OLED       │ │ GPS         │
        │ Left/Right │ │ LEDs       │ │ Notification│
        │            │ │            │ │             │
        └────────────┘ └────────────┘ └─────────────┘
                              │
                              ▼
                       ┌────────────┐
                       │ Buzzer /   │
                       │ Emergency  │
                       │   Siren    │
                       └────────────┘
### Main decision logic
        Radar detects nearby object
                    │
                    ▼
             Distance < 3 m?
               /          \
             NO            YES
             │              │
          No alert          ▼
                    Microphone detects
                    high sound level?
                       /        \
                     NO          YES
                     │            │
                  Monitor      HAZARD
                               DETECTED
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
              Left vibration   OLED/LED      Buzzer
              /Right vibration  warning       warning

### Basic flow

Radar   ───────────────┐
                       │
Microphone → Audio ML  ├──→ Sensor Fusion → Rider Warning
                       │
IMU → Crash Logic   ───┘

Crash → Countdown → Cancel / Emergency Alert

GPS → IoT/Cloud → Emergency Notification

---

## 7. Technology Stack

### Hardware

- ESP32
- Radar sensor
- Microphone
- MPU6050
- GPS module
- OLED display
- Vibration motors
- LEDs
- BJT transistor drivers
- Buzzer/siren

### Software

- ESP32 firmware
- Proteus simulation
- Audio ML/TinyML approach
- GitHub

### Communication

- Wi-Fi
- IoT/cloud communication

---

## 8. Current Progress

At the current stage, the project architecture and main system logic have been defined. 
The Proteus simulation is being developed to demonstrate the sensor inputs, control logic and actuator circuits.
The BJT-based vibration motor driver has been designed.
The main test scenarios have also been identified.
The current proof of concept focuses on demonstrating the system
logic and safety response using simulated inputs.

---

## 9. Simulation

Because exact simulation models may not be available for every
selected radar and microphone module, controllable inputs are used
to represent their outputs in the simulation.

The simulation is used to verify:

- Hazard decision logic
- Left/right warning
- Vibration motor control
- LED indication
- Crash detection
- Emergency countdown
- Cancel operation
- Local alert behavior

---

## 10. Testing

The following scenarios are being tested:

| Test| Situation                | Expected Result       |
|-----|------------------------- |-----------------------|
| T01 | No hazard                | SAFE                  |
| T02 | Object on left           | LEFT warning          |
| T03 | Object on right          | RIGHT warning         |
| T04 | Dog bark + nearby object | Possible dog hazard   |
| T05 | Human voice + object     | No dog classification |
| T06 | Vehicle sound            | No dog classification |
| T07 | Crash event              | Emergency countdown   |
| T08 | Cancel pressed           | Emergency cancelled   |
| T09 | Network unavailable      | Local safety continues|

Actual PASS/FAIL results will be recorded after testing.

---

## 11. Limitations

- Exact radar and microphone modules may not have matching Proteus
  simulation models.
- Radar alone cannot identify a dog.
- Outdoor sounds can affect audio classification.
- Sensor range may limit detection distance.
- Cloud emergency notification depends on network connectivity.
- The current system is a prototype/PoC and requires further
  hardware and field testing.

---

## 12. Future Improvements

- Integrate actual radar and microphone hardware
- Improve audio classification
- Perform outdoor testing
- Improve sensor-fusion logic
- Improve crash detection reliability
- Add more robust GPS/IoT communication
- Reduce false alarms
- Develop a compact wearable/vehicle-mounted prototype

---

## 13. Project Status

Current stage:

**Simulation + Control Logic + Proof-of-Concept Development**

---

## 14. Team

Team members:

1. Sewmini D.B.Y.L
2. Jayasuriya M.G.S.F
3. Jayasekara J.M.O.S
4. Kumaranayaka K.I.S


University of Ruhuna
Faculty of Engineering
