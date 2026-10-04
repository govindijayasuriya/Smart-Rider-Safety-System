# 3. System Architecture

The Smart Rider Safety & Hazard Mitigation System (SRSHMS) consists of sensing, processing, warning, crash detection, and IoT communication modules. The main controller is an ESP32, which receives information from the sensors and performs the required decision-making.

The system follows the concept of **Prevent → Protect → Rescue**.

### 3.1 Sensor Layer

The sensor layer consists of a radar sensor, microphone, and MPU6050 accelerometer/gyroscope.

* **Radar Sensor:** Detects nearby objects and their movement. It can provide information such as the presence, distance, and movement of an object. The radar is not used to identify whether the object is specifically a dog.
* **Microphone:** Captures surrounding sounds.
* **Audio ML Classifier:** Processes the microphone input and classifies sounds into categories such as Dog Bark, Human Voice, Vehicle/Traffic Sound, and Background Noise.
* **MPU6050:** Monitors acceleration and orientation changes to support crash detection.
* **GPS Module:** Provides the rider's location when an emergency event occurs.

### 3.2 Processing and Decision Layer

The ESP32 acts as the main controller. The radar information and audio classification result are combined using sensor-fusion logic.

For example, when a nearby moving object is detected by the radar and the audio classifier identifies a dog bark, the system can classify the situation as a possible dog-related hazard.

The system does not claim that radar can identify a dog. Instead, radar provides information about a nearby object while the audio ML classifier provides information about the sound. These two sources are combined to improve the hazard decision.

### 3.3 Rider Warning Layer

When a possible hazard is detected, the system provides directional feedback to the rider.

* Left-side hazard → Left vibration motor + Left LED
* Right-side hazard → Right vibration motor + Right LED
* Hazard information → OLED display

For example, the OLED can display:

**POSSIBLE HAZARD**
**LEFT – 2.4 m**

The vibration motors provide a direct physical warning so that the rider does not need to continuously look at the display.

### 3.4 Crash Detection and Emergency Layer

The MPU6050 is used to detect possible crash conditions by considering sudden acceleration/impact and abnormal orientation or movement.

When a possible crash is detected:

1. The system detects the crash condition.
2. A 10-second cancellation period is started.
3. If the rider cancels the alert, the emergency process is stopped.
4. If the rider does not cancel the alert, the local siren is activated.
5. GPS information can be used to obtain the rider's location.
6. The emergency information can be sent through the IoT communication system.

### 3.5 IoT Communication

The ESP32 provides Wi-Fi connectivity for communication with the cloud or mobile notification system.

If the Internet connection is unavailable, local safety functions such as hazard warning, crash detection, and the local siren should continue to operate. Only the remote notification function will be affected.

### 3.6 Overall Data Flow

Radar + Microphone
↓
Audio ML + Radar Processing
↓
Sensor Fusion
↓
ESP32 Decision Logic
↓
Left/Right Warning or Safe State

MPU6050
↓
Crash Detection
↓
10-Second Cancellation
↓
Cancel → Stop Emergency
OR
No Cancel → Siren + GPS + IoT Notification

This architecture separates local safety functions from remote IoT communication, allowing important warning and emergency functions to continue even when network connectivity is unavailable.
