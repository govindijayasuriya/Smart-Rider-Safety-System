// ESP8266 NodeMCU + MPU6050 + vibration motor
// Bench test only: accelerometer tilt is not validated for riding.
//
// PIN CONNECTIONS:
// D7 -> resistor -> motor driver transistor
// D5 -> 3V3 : simulated dog detected
// D5 -> GND : no dog
// D2 -> MPU6050 SDA
// D1 -> MPU6050 SCL
//
// Do not connect the motor directly to an ESP8266 GPIO.

#include <Arduino.h>
#include <Wire.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <math.h>
#include "secrets.h"

// ---------------- SETTINGS ----------------

constexpr uint8_t SENSOR = 0x68;

constexpr uint8_t MOTOR_PIN = D7;
constexpr uint8_t DOG_SIGNAL_PIN = D5;

constexpr float TILT_LIMIT = 60.0f;
constexpr float UPRIGHT_LIMIT = 20.0f;

constexpr uint32_t WINDOW_MS = 15000;
constexpr uint32_t HOLD_MS = 300;
constexpr uint32_t REARM_MS = 2000;

ESP8266WebServer server(80);

// ---------------- EVENT STATE ----------------

String state = "UNCALIBRATED";
String eventId;
String deviceId;
String bootId;

uint32_t eventCount = 0;
uint32_t pendingAt = 0;
uint32_t aboveAt = 0;
uint32_t uprightAt = 0;
uint32_t sampleAt = 0;
uint32_t printAt = 0;

bool above = false;
bool upright = false;
bool calibrated = false;
bool sensorOk = false;
bool acknowledged = false;
bool fsOk = false;

float rx = 0;
float ry = 0;
float rz = 1;
float tilt = 0;

// ---------------- MOTOR STATE ----------------

enum class VibrationMode {
  OFF,
  DOG,
  TILT
};

VibrationMode vibrationMode = VibrationMode::OFF;

bool dogDetected = false;
bool motorOn = false;

uint32_t patternStartedAt = 0;

void setMotor(bool on) {
  motorOn = on;
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
}

void stopMotor() {
  vibrationMode = VibrationMode::OFF;
  patternStartedAt = millis();
  setMotor(false);
}

void updateMotor() {
  // Bench simulation:
  // D5 HIGH = dog detected
  // D5 LOW  = no dog
  dogDetected = digitalRead(DOG_SIGNAL_PIN) == HIGH;

  bool immediateTilt =
      calibrated &&
      sensorOk &&
      state == "ARMED" &&
      tilt > TILT_LIMIT;

  // Keep vibrating through the countdown and until acknowledgement.
  bool activeEvent =
      state == "PENDING" ||
      (state == "CONFIRMED" && !acknowledged);

  VibrationMode wanted = VibrationMode::OFF;

  // Tilt warning has priority over dog warning.
  if (immediateTilt || activeEvent) {
    wanted = VibrationMode::TILT;
  } else if (dogDetected) {
    wanted = VibrationMode::DOG;
  }

  uint32_t now = millis();

  if (wanted != vibrationMode) {
    vibrationMode = wanted;
    patternStartedAt = now;
  }

  uint32_t elapsed = now - patternStartedAt;

  if (vibrationMode == VibrationMode::TILT) {
    // Longer pulses: ON 300ms, OFF 200ms.
    setMotor((elapsed % 500UL) < 300UL);

  } else if (vibrationMode == VibrationMode::DOG) {
    // Two short pulses every second.
    uint32_t phase = elapsed % 1000UL;

    bool on =
        phase < 120UL ||
        (phase >= 240UL && phase < 360UL);

    setMotor(on);

  } else {
    setMotor(false);
  }
}

// ---------------- MPU6050 ----------------

bool writeReg(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(SENSOR);
  Wire.write(reg);
  Wire.write(value);

  return Wire.endTransmission() == 0;
}

int16_t signed16() {
  uint16_t h = Wire.read();
  uint16_t l = Wire.read();

  return static_cast<int16_t>((h << 8) | l);
}

bool acceleration(float &x, float &y, float &z) {
  Wire.beginTransmission(SENSOR);
  Wire.write(0x3B);

  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  if (Wire.requestFrom(SENSOR, static_cast<uint8_t>(6)) != 6) {
    while (Wire.available()) {
      Wire.read();
    }

    return false;
  }

  x = signed16() / 16384.0f;
  y = signed16() / 16384.0f;
  z = signed16() / 16384.0f;

  float n = sqrtf(x * x + y * y + z * z);

  return isfinite(n) && n > 0.1f;
}

// ---------------- STORAGE ----------------

bool persistEvent() {
  if (!fsOk) {
    return false;
  }

  JsonDocument d;
  d["state"] = state;
  d["eventId"] = eventId;

  File f = LittleFS.open("/event.tmp", "w");

  if (!f) {
    return false;
  }

  size_t written = serializeJson(d, f);
  f.close();

  return written > 0 &&
         LittleFS.rename("/event.tmp", "/event.json");
}

bool removeStoredEvent() {
  if (fsOk && LittleFS.exists("/event.json")) {
    return LittleFS.remove("/event.json");
  }

  return true;
}

// ---------------- HTTP HELPERS ----------------

void error(int code, const char *message) {
  JsonDocument d;

  d["error"] = message;
  d["state"] = state;

  String body;
  serializeJson(d, body);

  server.send(code, "application/json", body);
}

bool authorized() {
  if (
      server.header("Authorization") ==
      String("Bearer ") + PAIRING_TOKEN
  ) {
    return true;
  }

  error(401, "unauthorized");
  return false;
}

void updateDeadline() {
  if (
      state == "PENDING" &&
      static_cast<uint32_t>(millis() - pendingAt) >= WINDOW_MS
  ) {
    state = "CONFIRMED";
    acknowledged = false;

    if (!persistEvent()) {
      Serial.println("WARNING: event persistence failed");
    }

    Serial.println("CONFIRMED - app may send SMS");
  }
}

void sendStatus() {
  updateDeadline();
  updateMotor();

  JsonDocument d;

  d["deviceId"] = deviceId;
  d["eventId"] = eventId;
  d["state"] = state;
  d["tiltDeg"] = tilt;

  uint32_t elapsed =
      static_cast<uint32_t>(millis() - pendingAt);

  d["remainingMs"] =
      state == "PENDING" && elapsed < WINDOW_MS
          ? WINDOW_MS - elapsed
          : 0;

  d["fw"] = "iotrix-bench-1.2";
  d["sensorOk"] = sensorOk;
  d["calibrated"] = calibrated;
  d["storageOk"] = fsOk;

  d["dogDetected"] = dogDetected;
  d["motorOn"] = motorOn;

  d["vibrationMode"] =
      vibrationMode == VibrationMode::TILT
          ? "TILT"
          : vibrationMode == VibrationMode::DOG
                ? "DOG"
                : "OFF";

  String body;
  serializeJson(d, body);

  server.send(200, "application/json", body);
}

bool matchingEvent() {
  JsonDocument d;

  if (deserializeJson(d, server.arg("plain"))) {
    error(400, "invalid_json");
    return false;
  }

  String id = d["eventId"] | "";

  if (id.isEmpty() || id != eventId) {
    error(409, "event_mismatch");
    return false;
  }

  return true;
}

// ---------------- CALIBRATION ----------------

bool calibrate() {
  stopMotor();

  float sx = 0;
  float sy = 0;
  float sz = 0;

  // Keep the sensor stationary and the bicycle upright.
  for (int i = 0; i < 100; i++) {
    float x, y, z;

    if (!acceleration(x, y, z)) {
      sensorOk = false;
      return false;
    }

    float n = sqrtf(x * x + y * y + z * z);

    if (n < 0.75f || n > 1.25f) {
      return false;
    }

    sx += x;
    sy += y;
    sz += z;

    delay(10);
  }

  float n = sqrtf(sx * sx + sy * sy + sz * sz);

  if (n < 1.0f) {
    return false;
  }

  rx = sx / n;
  ry = sy / n;
  rz = sz / n;

  calibrated = true;
  sensorOk = true;
  tilt = 0;

  above = false;
  upright = false;
  acknowledged = false;

  state = "ARMED";
  eventId = "";

  return true;
}

// ---------------- TILT / EVENT LOGIC ----------------

void sample() {
  float x, y, z;

  sensorOk = acceleration(x, y, z);

  if (!sensorOk) {
    above = false;
    upright = false;
    return;
  }

  if (!calibrated) {
    return;
  }

  float n = sqrtf(x * x + y * y + z * z);

  float cosine = constrain(
      (x * rx + y * ry + z * rz) / n,
      -1.0f,
      1.0f
  );

  tilt = acosf(cosine) * 180.0f / PI;

  uint32_t now = millis();

  if (state == "ARMED") {
    if (tilt > TILT_LIMIT) {
      if (!above) {
        above = true;
        aboveAt = now;
      }

      if (
          static_cast<uint32_t>(now - aboveAt) >= HOLD_MS
      ) {
        eventId = bootId + "-" + String(++eventCount);

        state = "PENDING";
        pendingAt = now;

        above = false;
        upright = false;
        acknowledged = false;

        if (!persistEvent()) {
          Serial.println(
              "WARNING: event persistence failed"
          );
        }

        Serial.println(
            "PENDING - 15 second cancel window"
        );
      }
    } else {
      above = false;
    }
  }

  bool canRearm =
      state == "CANCELLED" ||
      (state == "CONFIRMED" && acknowledged);

  if (canRearm) {
    if (tilt < UPRIGHT_LIMIT) {
      if (!upright) {
        upright = true;
        uprightAt = now;
      }

      if (
          static_cast<uint32_t>(now - uprightAt) >= REARM_MS
      ) {
        state = "ARMED";
        eventId = "";

        upright = false;
        above = false;
        acknowledged = false;

        Serial.println("ARMED again");
      }
    } else {
      upright = false;
    }
  }
}

// ---------------- SETUP ----------------

void setup() {
  digitalWrite(MOTOR_PIN, LOW);
  pinMode(MOTOR_PIN, OUTPUT);

  // D5 must be connected to GND or 3.3V.
  // Do not leave it floating.
  pinMode(DOG_SIGNAL_PIN, INPUT);

  stopMotor();

  Serial.begin(115200);

  // SDA = GPIO4 / D2
  // SCL = GPIO5 / D1
  Wire.begin(4, 5);
  Wire.setClock(100000);

  delay(300);

  bool configured = writeReg(0x6B, 0);
  delay(100);

  configured = writeReg(0x6C, 0) && configured;
  configured = writeReg(0x1C, 0) && configured;

  Serial.println(
      configured
          ? "Sensor configured"
          : "Sensor configuration failed - check wiring"
  );

  deviceId = "bike-" + String(ESP.getChipId(), HEX);

  fsOk = LittleFS.begin();

  uint32_t bootSequence = 0;

  if (fsOk) {
    File f = LittleFS.open("/boot.txt", "r");

    if (f) {
      bootSequence = f.parseInt();
      f.close();
    }

    f = LittleFS.open("/boot.txt", "w");

    if (f) {
      f.print(++bootSequence);
      f.close();
    } else {
      fsOk = false;
    }

    f = LittleFS.open("/event.json", "r");

    if (f) {
      JsonDocument d;

      if (!deserializeJson(d, f)) {
        eventId = String(d["eventId"] | "");

        if (!eventId.isEmpty()) {
          state = "CONFIRMED";
          acknowledged = false;
        }
      }

      f.close();
    }
  }

  bootId =
      deviceId + "-" +
      String(bootSequence) + "-" +
      String(micros(), HEX);

  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  // Correct format for your ESP8266 framework version.
  server.collectHeaders("Authorization");

  // ---------------- GET /status ----------------

  server.on("/status", HTTP_GET, []() {
    if (authorized()) {
      sendStatus();
    }
  });

  // ---------------- POST /cancel ----------------

  server.on("/cancel", HTTP_POST, []() {
    if (!authorized()) {
      return;
    }

    updateDeadline();

    if (!matchingEvent()) {
      return;
    }

    if (state == "CONFIRMED") {
      error(409, "too_late");
      return;
    }

    if (
        state != "PENDING" &&
        state != "CANCELLED"
    ) {
      error(409, "not_pending");
      return;
    }

    if (!removeStoredEvent()) {
      error(503, "storage_error");
      return;
    }

    state = "CANCELLED";
    upright = false;
    above = false;

    sendStatus();
  });

  // ---------------- POST /ack ----------------

  server.on("/ack", HTTP_POST, []() {
    if (!authorized()) {
      return;
    }

    updateDeadline();

    if (!matchingEvent()) {
      return;
    }

    if (state != "CONFIRMED") {
      error(409, "not_confirmed");
      return;
    }

    if (!removeStoredEvent()) {
      error(503, "storage_error");
      return;
    }

    acknowledged = true;
    upright = false;

    if (!calibrated) {
      state = "UNCALIBRATED";
      eventId = "";
    }

    sendStatus();
  });

  // ---------------- POST /calibrate ----------------

  server.on("/calibrate", HTTP_POST, []() {
    if (!authorized()) {
      return;
    }

    updateDeadline();

    if (
        state == "PENDING" ||
        state == "CONFIRMED"
    ) {
      error(409, "not_armed");
      return;
    }

    if (!calibrate()) {
      error(
          503,
          "keep_still_and_check_sensor"
      );
      return;
    }

    sendStatus();
  });

  server.onNotFound([]() {
    error(404, "not_found");
  });

  server.begin();

  Serial.println("Dog simulation: D5 HIGH = dog.");
  Serial.println("D5 LOW = no dog.");
  Serial.println("Calibrate upright using the app.");
}

// ---------------- MAIN LOOP ----------------

void loop() {
  updateDeadline();
  server.handleClient();

  uint32_t now = millis();

  if (
      static_cast<uint32_t>(now - sampleAt) >= 20UL
  ) {
    sampleAt = now;
    sample();
  }

  updateMotor();

  if (
      static_cast<uint32_t>(now - printAt) >= 2000UL
  ) {
    printAt = now;

    if (WiFi.status() == WL_CONNECTED) {
      Serial.print("Board IP: ");
      Serial.print(WiFi.localIP());
    } else {
      Serial.print("WiFi disconnected");
    }

    Serial.print(" | ");
    Serial.print(state);

    Serial.print(" | tilt: ");
    Serial.print(tilt, 1);

    Serial.print(" | sensor: ");
    Serial.print(sensorOk ? "OK" : "ERROR");

    Serial.print(" | dog: ");
    Serial.print(dogDetected ? "YES" : "NO");

    Serial.print(" | vibration: ");

    if (vibrationMode == VibrationMode::TILT) {
      Serial.print("TILT");
    } else if (vibrationMode == VibrationMode::DOG) {
      Serial.print("DOG");
    } else {
      Serial.print("OFF");
    }

    Serial.print(" | motor: ");
    Serial.println(motorOn ? "ON" : "OFF");
  }

  yield();
}