// ESP8266 NodeMCU bench firmware
// MPU6050 SDA -> D3 / GPIO0
// MPU6050 SCL -> D4 / GPIO2
// Motor transistor control -> D7
// Dog simulation input -> D5
//
// D5 -> 3V3 = dog detected
// D5 -> GND = no dog
// Never leave D5 floating or connect it to 5V.
//
// Accelerometer-only tilt: not validated for riding.
// Motor must use a suitable driver/transistor circuit.

// ESP8266 NodeMCU bench integration firmware.
//
// MPU6050: SDA D3, SCL D4, AD0 GND.
// Motor driver: D7.
// Dog simulation: D5 HIGH = dog, LOW = no dog.
// Cancel button: D6 to GND, normally open.
// Active 5V buzzer through a SEPARATE driver: D0.
//
// Never connect motor/buzzer loads directly to GPIO.
// Accelerometer-only tilt: not validated for riding.

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
constexpr uint8_t SDA_PIN = D3;
constexpr uint8_t SCL_PIN = D4;
constexpr uint8_t MOTOR_PIN = D7;
constexpr uint8_t DOG_PIN = D5;
constexpr uint8_t CANCEL_PIN = D6;
constexpr uint8_t BUZZER_PIN = D0;

constexpr float TILT_LIMIT = 60.0f;
constexpr float UPRIGHT_LIMIT = 20.0f;

constexpr uint32_t WINDOW_MS = 15000;
constexpr uint32_t HOLD_MS = 300;
constexpr uint32_t REARM_MS = 2000;
constexpr uint32_t DEBOUNCE_MS = 40;

ESP8266WebServer server(80);

// ---------------- STATE ----------------

String state = "UNCALIBRATED";
String eventId, deviceId, bootId;

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

float rx = 0, ry = 0, rz = 1, tilt = 0;

enum class VibrationMode { OFF, DOG, TILT };
VibrationMode vibrationMode = VibrationMode::OFF;

uint32_t patternStartedAt = 0;
bool motorOn = false;
bool buzzerOn = false;
bool dogDetected = false;

bool buttonRaw = HIGH;
bool buttonStable = HIGH;
uint32_t buttonChangedAt = 0;

// ---------------- OUTPUTS ----------------

void setMotor(bool on) {
  motorOn = on;
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
}

void setBuzzer(bool on) {
  buzzerOn = on;
  digitalWrite(BUZZER_PIN, on ? HIGH : LOW);
}

void stopOutputs() {
  vibrationMode = VibrationMode::OFF;
  patternStartedAt = millis();
  setMotor(false);
  setBuzzer(false);
}

void updateOutputs() {
  dogDetected = digitalRead(DOG_PIN) == HIGH;

  bool activeEvent =
      state == "PENDING" ||
      (state == "CONFIRMED" && !acknowledged);

  bool immediateTilt =
      calibrated && sensorOk &&
      state == "ARMED" && tilt > TILT_LIMIT;

  VibrationMode wanted = VibrationMode::OFF;

  if (activeEvent || immediateTilt) {
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
    setMotor((elapsed % 500UL) < 300UL);
  } else if (vibrationMode == VibrationMode::DOG) {
    uint32_t phase = elapsed % 1000UL;
    setMotor(
        phase < 120UL ||
        (phase >= 240UL && phase < 360UL)
    );
  } else {
    setMotor(false);
  }

  // Buzzer starts ONLY after countdown expires.
  setBuzzer(state == "CONFIRMED" && !acknowledged);
}

// ---------------- SENSOR ----------------

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

  if (Wire.endTransmission(false) != 0) return false;

  if (Wire.requestFrom(SENSOR, (uint8_t)6) != 6) {
    while (Wire.available()) Wire.read();
    return false;
  }

  x = signed16() / 16384.0f;
  y = signed16() / 16384.0f;
  z = signed16() / 16384.0f;

  float n = sqrtf(x*x + y*y + z*z);
  return isfinite(n) && n > 0.1f;
}

// ---------------- STORAGE ----------------

bool persistEvent() {
  if (!fsOk) return false;

  JsonDocument d;
  d["state"] = state;
  d["eventId"] = eventId;

  File f = LittleFS.open("/event.tmp", "w");
  if (!f) return false;

  size_t n = serializeJson(d, f);
  f.close();

  return n > 0 &&
         LittleFS.rename("/event.tmp", "/event.json");
}

bool removeStoredEvent() {
  if (fsOk && LittleFS.exists("/event.json")) {
    return LittleFS.remove("/event.json");
  }
  return true;
}

// ---------------- EVENT CONTROL ----------------

void updateDeadline() {
  if (
      state == "PENDING" &&
      (uint32_t)(millis() - pendingAt) >= WINDOW_MS
  ) {
    state = "CONFIRMED";
    acknowledged = false;

    if (!persistEvent()) {
      Serial.println("WARNING: event persistence failed");
    }

    Serial.println("CONFIRMED - app may send SMS");
  }
}

// Returns false if persistent cancellation could not be saved.
bool cancelCurrentEvent() {
  if (state != "PENDING" &&
      state != "CONFIRMED" &&
      state != "CANCELLED") {
    return true;
  }

  // Stop local alarm immediately, even if storage later fails.
  stopOutputs();

  if (!removeStoredEvent()) {
    Serial.println("Cancel failed: storage error");
    return false;
  }

  state = "CANCELLED";
  acknowledged = false;
  upright = false;
  above = false;

  Serial.println("CANCELLED - local alarm stopped");
  return true;
}

void pollCancelButton() {
  bool reading = digitalRead(CANCEL_PIN);
  uint32_t now = millis();

  if (reading != buttonRaw) {
    buttonRaw = reading;
    buttonChangedAt = now;
  }

  if (
      (uint32_t)(now - buttonChangedAt) >= DEBOUNCE_MS &&
      buttonStable != buttonRaw
  ) {
    buttonStable = buttonRaw;

    if (buttonStable == LOW) {
      if (!cancelCurrentEvent()) {
        // Event remains active if cancellation could not persist.
        Serial.println("WARNING: cancellation not saved");
      }
    }
  }
}

// ---------------- HTTP ----------------

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
  ) return true;

  error(401, "unauthorized");
  return false;
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

void sendStatus() {
  updateDeadline();
  updateOutputs();

  JsonDocument d;
  d["deviceId"] = deviceId;
  d["eventId"] = eventId;
  d["state"] = state;
  d["tiltDeg"] = tilt;

  uint32_t elapsed = (uint32_t)(millis() - pendingAt);
  d["remainingMs"] =
      state == "PENDING" && elapsed < WINDOW_MS
          ? WINDOW_MS - elapsed : 0;

  d["fw"] = "iotrix-bench-1.4";
  d["sensorOk"] = sensorOk;
  d["calibrated"] = calibrated;
  d["storageOk"] = fsOk;
  d["dogDetected"] = dogDetected;
  d["motorOn"] = motorOn;
  d["buzzerOn"] = buzzerOn;
  d["cancelPressed"] = buttonStable == LOW;

  d["vibrationMode"] =
      vibrationMode == VibrationMode::TILT ? "TILT" :
      vibrationMode == VibrationMode::DOG ? "DOG" : "OFF";

  String body;
  serializeJson(d, body);
  server.send(200, "application/json", body);
}

// ---------------- CALIBRATION ----------------

bool calibrate() {
  stopOutputs();

  float sx = 0, sy = 0, sz = 0;

  for (int i = 0; i < 100; i++) {
    float x, y, z;

    if (!acceleration(x, y, z)) {
      sensorOk = false;
      return false;
    }

    float n = sqrtf(x*x + y*y + z*z);
    if (n < 0.75f || n > 1.25f) return false;

    sx += x;
    sy += y;
    sz += z;
    delay(10);
  }

  float n = sqrtf(sx*sx + sy*sy + sz*sz);
  if (n < 1.0f) return false;

  rx = sx/n;
  ry = sy/n;
  rz = sz/n;

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

// ---------------- TILT SAMPLING ----------------

void sample() {
  float x, y, z;
  sensorOk = acceleration(x, y, z);

  if (!sensorOk) {
    above = false;
    upright = false;
    return;
  }

  if (!calibrated) return;

  float n = sqrtf(x*x + y*y + z*z);

  tilt = acosf(constrain(
      (x*rx + y*ry + z*rz)/n,
      -1.0f, 1.0f
  )) * 180.0f / PI;

  uint32_t now = millis();

  if (state == "ARMED") {
    if (tilt > TILT_LIMIT) {
      if (!above) {
        above = true;
        aboveAt = now;
      }

      if ((uint32_t)(now - aboveAt) >= HOLD_MS) {
        eventId = bootId + "-" + String(++eventCount);
        state = "PENDING";
        pendingAt = now;
        acknowledged = false;
        above = false;
        upright = false;

        if (!persistEvent()) {
          Serial.println("WARNING: event persistence failed");
        }

        Serial.println("PENDING - 15 second cancel window");
      }
    } else {
      above = false;
    }
  }

  if (
      state == "CANCELLED" ||
      (state == "CONFIRMED" && acknowledged)
  ) {
    // Release the button and return upright before rearming.
    if (tilt < UPRIGHT_LIMIT && buttonStable == HIGH) {
      if (!upright) {
        upright = true;
        uprightAt = now;
      }

      if ((uint32_t)(now - uprightAt) >= REARM_MS) {
        state = "ARMED";
        eventId = "";
        above = false;
        upright = false;
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

  digitalWrite(BUZZER_PIN, LOW);
  pinMode(BUZZER_PIN, OUTPUT);

  pinMode(DOG_PIN, INPUT);
  pinMode(CANCEL_PIN, INPUT_PULLUP);

  buttonRaw = digitalRead(CANCEL_PIN);
  // Start HIGH so a button held during boot is also detected.
  buttonStable = HIGH;
  buttonChangedAt = millis();

  stopOutputs();

  Serial.begin(115200);
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);

  delay(300);

  bool configured = writeReg(0x6B, 0);
  delay(100);
  configured = writeReg(0x6C, 0) && configured;
  configured = writeReg(0x1C, 0) && configured;

  Serial.println(
      configured ? "Sensor configured: SDA D3, SCL D4" :
                   "Sensor configuration failed"
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

  bootId = deviceId + "-" + String(bootSequence) +
           "-" + String(micros(), HEX);

  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  server.collectHeaders("Authorization");

  server.on("/status", HTTP_GET, []() {
    if (authorized()) sendStatus();
  });

  server.on("/cancel", HTTP_POST, []() {
    if (!authorized()) return;

    updateDeadline();
    if (!matchingEvent()) return;

    if (state != "PENDING" &&
        state != "CONFIRMED" &&
        state != "CANCELLED") {
      error(409, "not_pending");
      return;
    }

    // Also permits silencing a confirmed event.
    // An SMS already sent cannot be recalled.
    if (!cancelCurrentEvent()) {
      error(503, "storage_error");
      return;
    }

    sendStatus();
  });

  server.on("/ack", HTTP_POST, []() {
    if (!authorized()) return;

    updateDeadline();
    if (!matchingEvent()) return;

    // App may acknowledge a message already in flight
    // after the local button has cancelled the event.
    if (state == "CANCELLED") {
      sendStatus();
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

  server.on("/calibrate", HTTP_POST, []() {
    if (!authorized()) return;

    updateDeadline();

    if (state == "PENDING" || state == "CONFIRMED") {
      error(409, "not_armed");
      return;
    }

    if (!calibrate()) {
      error(503, "keep_still_and_check_sensor");
      return;
    }

    sendStatus();
  });

  server.onNotFound([]() {
    error(404, "not_found");
  });

  server.begin();

  Serial.println("Motor D7 | Dog D5 | Cancel D6 | Buzzer D0");
  Serial.println("Calibrate upright using the app.");
}

// ---------------- LOOP ----------------

void loop() {
  // Check physical cancellation before expiring countdown.
  pollCancelButton();
  updateDeadline();
  server.handleClient();

  uint32_t now = millis();

  if ((uint32_t)(now - sampleAt) >= 20UL) {
    sampleAt = now;
    sample();
  }

  updateOutputs();

  if ((uint32_t)(now - printAt) >= 2000UL) {
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
    Serial.print(" | motor: ");
    Serial.print(motorOn ? "ON" : "OFF");
    Serial.print(" | buzzer: ");
    Serial.print(buzzerOn ? "ON" : "OFF");
    Serial.print(" | button: ");
    Serial.println(buttonStable == LOW ? "PRESSED" : "RELEASED");
  }

  yield();
}