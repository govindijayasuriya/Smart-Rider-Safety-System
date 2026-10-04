# 5. Testing and Validation

Testing is carried out using different input conditions to verify whether the system produces the expected warning and emergency responses. The main objective is to verify the sensor-fusion logic, directional warning system, crash response, and network-failure behavior.

### 5.1 Test Cases

| Test ID | Input Condition                        | Expected Result                                      |
| ------- | -------------------------------------- | ---------------------------------------------------- |
| T01     | No nearby object and no hazard sound   | SAFE state                                           |
| T02     | Object detected on the left            | Left warning activated                               |
| T03     | Object detected on the right           | Right warning activated                              |
| T04     | Dog bark + nearby object on left       | Possible left-side dog hazard                        |
| T05     | Dog bark + nearby object on right      | Possible right-side dog hazard                       |
| T06     | Human voice + nearby object            | No dog-related classification                        |
| T07     | Vehicle/traffic sound + nearby object  | No dog-related classification                        |
| T08     | Dog bark but no nearby object          | Audio event detected, but no confirmed nearby hazard |
| T09     | Crash condition detected               | Crash countdown starts                               |
| T10     | Cancel button pressed during countdown | Emergency cancelled                                  |
| T11     | No cancellation after crash            | Local siren and emergency response activated         |
| T12     | Internet unavailable                   | Local safety functions continue                      |

### 5.2 Sensor-Fusion Validation

Sensor fusion is tested using different combinations of radar and audio-classification inputs.

| Radar Result     | Audio Result     | System Decision                               |
| ---------------- | ---------------- | --------------------------------------------- |
| Nearby object    | Dog Bark         | Possible dog-related hazard                   |
| Nearby object    | Human Voice      | No dog classification                         |
| Nearby object    | Vehicle Sound    | No dog classification                         |
| No nearby object | Dog Bark         | Audio event detected but hazard not confirmed |
| No nearby object | Background Noise | SAFE                                          |

This test helps prevent the system from treating every detected sound as a dog-related hazard.

### 5.3 Directional Warning Validation

The left and right warning systems are tested independently.

**Left-side condition:**

Radar detects an object on the left.

Expected:

* Left LED ON
* Left vibration motor ON
* Right vibration motor OFF
* OLED indicates left-side hazard

**Right-side condition:**

Radar detects an object on the right.

Expected:

* Right LED ON
* Right vibration motor ON
* Left vibration motor OFF
* OLED indicates right-side hazard

### 5.4 Crash Detection Validation

The crash response is tested using a simulated crash input.

The expected sequence is:

1. Crash condition detected.
2. 10-second cancellation period begins.
3. Rider can press the cancel button.
4. If cancelled, the emergency response stops.
5. If not cancelled, the local siren is activated.
6. GPS/location information is prepared for the emergency notification.

### 5.5 Network Failure Validation

The system is also considered under network-loss conditions.

When Wi-Fi/Internet is available:

**Crash → ESP32 → IoT service → Emergency notification**

When Wi-Fi/Internet is unavailable:

**Crash → ESP32 → Local siren**

The local safety functions should continue operating even when remote communication is unavailable.

### 5.6 Limitations Identified During Testing

The current proof of concept has several limitations:

* The exact radar module may not have a corresponding Proteus simulation model.
* The exact microphone module may not have a corresponding Proteus simulation model.
* Simulated sensor inputs cannot fully represent real-world sensor behavior.
* Radar can detect a nearby object but cannot identify its species.
* Audio classification can be affected by traffic, wind, and other environmental noise.
* Real outdoor road-side testing has not yet been completed.
* Remote emergency notification depends on network connectivity.
* Actual hardware integration is required for final real-world validation.

### 5.7 Planned Validation Improvements

The next stage of validation will include:

1. Connecting the actual radar module.
2. Connecting the actual microphone.
3. Implementing the audio ML classifier on the target hardware.
4. Testing the system in different outdoor environments.
5. Testing the system with traffic and background noise.
6. Measuring false-positive and false-negative cases.
7. Testing crash detection using controlled conditions.
8. Integrating and testing GPS.
9. Testing real IoT emergency notifications.
10. Performing complete hardware-level validation.

The purpose of the current testing stage is to verify the main system logic and demonstrate a credible proof of concept before moving to full hardware implementation.
