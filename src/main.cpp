#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include "secrets.h"

// ================= PINS =================

constexpr uint8_t DOG_PIN    = D5;
constexpr uint8_t BARK_PIN   = D1;
constexpr uint8_t FALL_PIN   = D2;
constexpr uint8_t MOTOR_PIN  = D7;
constexpr uint8_t CANCEL_PIN = D6;
constexpr uint8_t BUZZER_PIN = D0;

// Inputs: HIGH = active, valid 3.3V logic.
// Cancel button: D6 to GND, active LOW.
// Motor and buzzer outputs control driver circuits.

// ================= SETTINGS =================

constexpr uint32_t COUNTDOWN_MS = 15000;
constexpr uint32_t REARM_MS = 2000;
constexpr uint32_t DEBOUNCE_MS = 40;

constexpr uint32_t DOG_WINDOW_MS = 3000;
constexpr uint32_t CHASE_HOLD_MS = 1000;

ESP8266WebServer server(80);

// ================= EVENT STATE =================

String state = "ARMED";
String eventId;
String deviceId;
String bootId;

uint32_t eventCount = 0;
uint32_t pendingAt = 0;
uint32_t uprightAt = 0;
uint32_t printAt = 0;

bool uprightTiming = false;
bool acknowledged = false;
bool fsOk = false;

// ================= INPUTS =================

bool dogHigh = false;
bool barkHigh = false;
bool fallHigh = false;

bool dogSeen = false;
bool chasing = false;

uint32_t lastDogAt = 0;
uint32_t lastMatchAt = 0;

// ================= BUTTON =================

bool buttonRaw = HIGH;
bool buttonStable = HIGH;
uint32_t buttonChangedAt = 0;

// ================= OUTPUTS =================

enum class MotorMode {
  OFF,
  DOG,
  EMERGENCY
};

MotorMode motorMode = MotorMode::OFF;

uint32_t patternAt = 0;
bool motorOn = false;
bool buzzerOn = false;

// ================= INPUT PROCESSING =================

void readInputs() {
  uint32_t now = millis();

  dogHigh = digitalRead(DOG_PIN) == HIGH;
  barkHigh = digitalRead(BARK_PIN) == HIGH;
  fallHigh = digitalRead(FALL_PIN) == HIGH;

  if (dogHigh) {
    dogSeen = true;
    lastDogAt = now;
  }

  if (
    dogSeen &&
    !dogHigh &&
    (uint32_t)(now - lastDogAt) > DOG_WINDOW_MS
  ) {
    dogSeen = false;
  }

  // Camera dog detection must precede or overlap bark.
  if (dogSeen && barkHigh) {
    chasing = true;
    lastMatchAt = now;

  } else if (
    chasing &&
    (uint32_t)(now - lastMatchAt) >= CHASE_HOLD_MS
  ) {
    chasing = false;
  }
}

// ================= OUTPUT CONTROL =================

void setMotor(bool on) {
  motorOn = on;
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
}

void setBuzzer(bool on) {
  buzzerOn = on;
  digitalWrite(BUZZER_PIN, on ? HIGH : LOW);
}

void updateOutputs() {
  MotorMode wanted = MotorMode::OFF;

  if (state == "PENDING" || state == "CONFIRMED") {
    // Immediate fall vibration during countdown,
    // continuing after countdown expires.
    wanted = MotorMode::EMERGENCY;

  } else if (chasing) {
    wanted = MotorMode::DOG;
  }

  uint32_t now = millis();

  if (wanted != motorMode) {
    motorMode = wanted;
    patternAt = now;
  }

  uint32_t elapsed = now - patternAt;

  if (motorMode == MotorMode::DOG) {
    uint32_t phase = elapsed % 1000UL;

    setMotor(
      phase < 150UL ||
      (phase >= 300UL && phase < 450UL)
    );

  } else if (motorMode == MotorMode::EMERGENCY) {
    setMotor((elapsed % 800UL) < 500UL);

  } else {
    setMotor(false);
  }

  // BUZZER:
  // ARMED     -> OFF
  // PENDING   -> OFF throughout the 15-second countdown
  // CONFIRMED -> ON
  // CANCELLED -> OFF
  //
  // Message ACK does not silence the buzzer.
  // App Cancel or physical Cancel silences it.
  setBuzzer(state == "CONFIRMED");
}

// ================= STORAGE =================

bool persistEvent() {
  if (!fsOk) return false;

  JsonDocument d;

  d["state"] = state;
  d["eventId"] = eventId;
  d["acknowledged"] = acknowledged;

  File f = LittleFS.open("/event.tmp", "w");
  if (!f) return false;

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

// ================= EVENT LOGIC =================

void updateDeadline() {
  if (
    state == "PENDING" &&
    (uint32_t)(millis() - pendingAt) >= COUNTDOWN_MS
  ) {
    state = "CONFIRMED";
    acknowledged = false;

    updateOutputs();

    if (!persistEvent()) {
      Serial.println("WARNING: event persistence failed");
    }

    Serial.println(
      "CONFIRMED - buzzer ON; app may send SMS"
    );
  }
}

bool cancelEvent() {
  if (
    state != "PENDING" &&
    state != "CONFIRMED" &&
    state != "CANCELLED"
  ) {
    return true;
  }

  if (!removeStoredEvent()) {
    Serial.println("Cancel failed: storage error");
    return false;
  }

  state = "CANCELLED";
  uprightTiming = false;

  // Stops buzzer and fall vibration immediately.
  // Separate chasing vibration can still operate.
  updateOutputs();

  Serial.println("CANCELLED - buzzer OFF");
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
      cancelEvent();
    }
  }
}

void processFall() {
  uint32_t now = millis();

  // No fall hold delay.
  if (
    state == "ARMED" &&
    fallHigh &&
    buttonStable == HIGH
  ) {
    eventId = bootId + "-" + String(++eventCount);

    state = "PENDING";
    pendingAt = now;
    acknowledged = false;
    uprightTiming = false;

    // Motor starts immediately; buzzer stays OFF.
    updateOutputs();

    if (!persistEvent()) {
      Serial.println("WARNING: event persistence failed");
    }

    Serial.println(
      "PENDING - vibration ON, buzzer OFF, 15s to cancel"
    );
  }

  // Return upright and release Cancel before rearming.
  if (state == "CANCELLED") {
    if (!fallHigh && buttonStable == HIGH) {
      if (!uprightTiming) {
        uprightTiming = true;
        uprightAt = now;
      }

      if ((uint32_t)(now - uprightAt) >= REARM_MS) {
        state = "ARMED";
        eventId = "";
        acknowledged = false;
        uprightTiming = false;

        Serial.println("ARMED again");
      }

    } else {
      uprightTiming = false;
    }
  }
}

// ================= HTTP HELPERS =================

void sendError(int code, const char* message) {
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

  sendError(401, "unauthorized");
  return false;
}

bool matchingEvent() {
  JsonDocument d;

  if (deserializeJson(d, server.arg("plain"))) {
    sendError(400, "invalid_json");
    return false;
  }

  String id = d["eventId"] | "";

  if (id.isEmpty() || id != eventId) {
    sendError(409, "event_mismatch");
    return false;
  }

  return true;
}

void sendStatus() {
  readInputs();
  processFall();
  updateDeadline();
  updateOutputs();

  JsonDocument d;

  d["deviceId"] = deviceId;
  d["eventId"] = eventId;
  d["state"] = state;

  uint32_t elapsed = millis() - pendingAt;

  d["remainingMs"] =
    state == "PENDING" && elapsed < COUNTDOWN_MS
      ? COUNTDOWN_MS - elapsed : 0;

  d["fw"] = "iotrix-countdown-buzzer-1.6";

  d["dogDetected"] = dogHigh;
  d["barkDetected"] = barkHigh;
  d["chasing"] = chasing;
  d["fallDetected"] = fallHigh;

  // Digital inputs do not transmit actual angle or health.
  d["tiltAvailable"] = false;
  d["sensorHealthKnown"] = false;

  // Legacy app compatibility fields only.
  d["tiltDeg"] = 0; // Placeholder, not a measured angle.
  d["calibrated"] = true;
  d["sensorOk"] = true; // Not external sensor health proof.

  d["storageOk"] = fsOk;
  d["acknowledged"] = acknowledged;
  d["motorOn"] = motorOn;
  d["buzzerOn"] = buzzerOn;
  d["cancelPressed"] = buttonStable == LOW;

  d["vibrationMode"] =
    motorMode == MotorMode::EMERGENCY ? "EMERGENCY" :
    motorMode == MotorMode::DOG ? "DOG" : "OFF";

  String body;
  serializeJson(d, body);

  server.send(200, "application/json", body);
}

// ================= SETUP =================

void setup() {
  digitalWrite(MOTOR_PIN, LOW);
  pinMode(MOTOR_PIN, OUTPUT);

  digitalWrite(BUZZER_PIN, LOW);
  pinMode(BUZZER_PIN, OUTPUT);

  pinMode(DOG_PIN, INPUT);
  pinMode(BARK_PIN, INPUT);
  pinMode(FALL_PIN, INPUT);
  pinMode(CANCEL_PIN, INPUT_PULLUP);

  buttonRaw = digitalRead(CANCEL_PIN);
  buttonStable = HIGH;
  buttonChangedAt = millis();

  Serial.begin(115200);
  delay(200);

  Serial.println();
  Serial.println("=== IOTRIX COUNTDOWN BUZZER 1.6 ===");
  Serial.println("DOG D5 | BARK D1 | FALL D2");
  Serial.println("MOTOR D7 | CANCEL D6 | BUZZER DRIVER D0");

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

    // Recover an unfinished event after restart.
    f = LittleFS.open("/event.json", "r");

    if (f) {
      JsonDocument d;

      if (!deserializeJson(d, f)) {
        eventId = String(d["eventId"] | "");

        if (!eventId.isEmpty()) {
          state = "CONFIRMED";
          acknowledged = d["acknowledged"] | false;
        }
      }

      f.close();
    }
  }

  bootId =
    deviceId + "-" + String(bootSequence) +
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

    if (
      state != "PENDING" &&
      state != "CONFIRMED" &&
      state != "CANCELLED"
    ) {
      sendError(409, "not_pending");
      return;
    }

    if (!cancelEvent()) {
      sendError(503, "storage_error");
      return;
    }

    sendStatus();
  });

  server.on("/ack", HTTP_POST, []() {
    if (!authorized()) return;

    updateDeadline();

    if (!matchingEvent()) return;

    if (state == "CANCELLED") {
      sendStatus();
      return;
    }

    if (state != "CONFIRMED") {
      sendError(409, "not_confirmed");
      return;
    }

    acknowledged = true;

    if (!persistEvent()) {
      Serial.println("WARNING: acknowledgement not saved");
    }

    // ACK records message handling.
    // Buzzer and emergency vibration remain ON until Cancel.
    sendStatus();
  });

  server.on("/calibrate", HTTP_POST, []() {
    if (!authorized()) return;

    updateDeadline();
    readInputs();

    if (state == "PENDING" || state == "CONFIRMED") {
      sendError(409, "not_armed");
      return;
    }

    if (fallHigh) {
      sendError(409, "return_bike_upright");
      return;
    }

    // The external board measures the angle.
    sendStatus();
  });

  server.onNotFound([]() {
    sendError(404, "not_found");
  });

  server.begin();
}

// ================= LOOP =================

void loop() {
  readInputs();
  pollCancelButton();

  processFall();
  updateDeadline();
  updateOutputs();

  server.handleClient();

  if ((uint32_t)(millis() - printAt) >= 500UL) {
    printAt = millis();

    Serial.print("DOG(D5)=");
    Serial.print(dogHigh ? "HIGH" : "LOW");

    Serial.print(" | BARK(D1)=");
    Serial.print(barkHigh ? "HIGH" : "LOW");

    Serial.print(" | FALL(D2)=");
    Serial.print(fallHigh ? "HIGH" : "LOW");

    Serial.print(" | CHASING=");
    Serial.print(chasing ? "YES" : "NO");

    Serial.print(" | STATE=");
    Serial.print(state);

    Serial.print(" | MOTOR=");
    Serial.print(motorOn ? "ON" : "OFF");

    Serial.print(" | BUZZER=");
    Serial.print(buzzerOn ? "ON" : "OFF");

    Serial.print(" | CANCEL=");
    Serial.print(
      buttonStable == LOW ? "PRESSED" : "RELEASED"
    );

    if (WiFi.status() == WL_CONNECTED) {
      Serial.print(" | IP=");
      Serial.print(WiFi.localIP());
    } else {
      Serial.print(" | WiFi disconnected");
    }

    Serial.println();
  }

  yield();
}