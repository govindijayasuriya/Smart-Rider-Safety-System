# 4. Current Implementation and Proof of Concept

The current development stage focuses on building the system logic, simulation, and initial proof of concept. The main system architecture has been defined and the required sensing, warning, and emergency-response scenarios have been identified.

### 4.1 Current Development Status

The following parts of the system have been planned and are currently being developed:

* Overall SRSHMS system architecture
* Radar-based nearby-object detection logic
* Microphone and audio-classification concept
* Sensor-fusion decision logic
* Left and right directional warning logic
* Vibration motor control
* LED warning indicators
* OLED status display
* Crash detection logic
* 10-second emergency cancellation process
* Local emergency siren logic
* GPS and IoT emergency-notification concept
* Proteus simulation
* ESP32 control code
* Testing scenarios

### 4.2 Simulation Proof of Concept

A Proteus-based simulation is being used to demonstrate the main control and warning logic.

Since an exact simulation model for the selected radar and microphone modules may not be available in Proteus, controllable input sources are used to represent their outputs.

For example:

* A simulated input represents radar object detection.
* A potentiometer/input value can represent distance information.
* Switches can represent different audio-classification outputs such as Dog Bark, Human Voice, Vehicle Sound, and Background Noise.
* A switch can represent a crash event.
* Another switch can represent the emergency cancellation button.

This allows the main decision-making and actuator-control logic to be tested without claiming that the actual radar and microphone hardware are being simulated.

### 4.3 Vibration Motor Driver

The vibration motors are controlled through transistor driver circuits rather than directly from the ESP32 GPIO pins.

The driver consists of:

* ESP32/control signal
* Base resistor
* BJT transistor
* Vibration motor
* Flyback diode
* Power supply

The transistor acts as a switch to control the motor safely.

Separate driver circuits are used for the left and right vibration motors so that the system can provide directional warnings.

### 4.4 Hazard Detection Demonstration

The simulation will demonstrate different hazard conditions.

#### Normal Condition

No nearby object is detected and no relevant hazard sound is detected.

**Expected output:**
SAFE

#### Left-Side Possible Hazard

A nearby object is detected on the left and the audio classifier indicates a dog bark.

**Expected output:**

* Left LED ON
* Left vibration motor ON
* OLED displays possible left-side hazard

#### Right-Side Possible Hazard

A nearby object is detected on the right and the audio classifier indicates a dog bark.

**Expected output:**

* Right LED ON
* Right vibration motor ON
* OLED displays possible right-side hazard

#### Human Voice

A nearby object is detected but the audio classifier identifies human voice.

**Expected output:**

* No dog-related classification
* System does not treat the event as a confirmed dog hazard

### 4.5 Crash Demonstration

A crash input is provided to the system to represent a possible crash event.

The expected sequence is:

Crash detected
↓
10-second countdown
↓
Cancel button pressed → Emergency cancelled

OR

Crash detected
↓
10-second countdown
↓
No cancellation
↓
Local siren activated
↓
GPS/location information prepared
↓
IoT emergency notification

### 4.6 Current Proof-of-Concept Evidence

The following evidence will be included in the project repository:

* Complete simulation circuit
* Vibration motor driver circuit
* Left-side warning simulation
* Right-side warning simulation
* Human-voice scenario
* Dog-bark scenario
* Crash detection scenario
* Emergency cancellation scenario
* Source code
* Simulation screenshots
* Test-case documentation

Only tests that are actually completed will be marked as successful.
