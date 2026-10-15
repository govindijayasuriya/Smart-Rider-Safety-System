/*
  IoTrix board firmware  v1.0.0   (ESP8266 NodeMCU + MPU6050)

  The board does ALL the safety logic:
    - measures tilt with the MPU6050,
    - detects tilt > 60 degrees,
    - runs the 15 second countdown,
    - lets the handlebar button cancel.
  The phone app only polls GET /status and sends /cancel and /ack. See API.md.

  Arduino IDE setup:
    Board manager URL : http://arduino.esp8266.com/stable/package_esp8266com_index.json
    Install           : "esp8266 by ESP8266 Community"
    Board             : NodeMCU 1.0 (ESP-12E Module)
    Serial Monitor    : 115200 baud (the board prints its IP and token there)
  No extra libraries are needed.

  Wiring (NodeMCU pin labels):
    MPU6050 VCC -> 3V3      MPU6050 GND -> GND
    MPU6050 SDA -> D2       MPU6050 SCL -> D1
    Handlebar push button between D5 and GND  (no resistor needed, uses the internal pull-up)
    Active buzzer (+) -> D6, buzzer (-) -> GND   (optional)
*/

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <Wire.h>
#include <math.h>

// ======================= CONFIG: EDIT THESE =======================
const char* WIFI_SSID      = "YOUR_PHONE_HOTSPOT_NAME";   // 2.4 GHz hotspot only
const char* WIFI_PASS      = "YOUR_HOTSPOT_PASSWORD";
const char* PAIRING_TOKEN  = "change-me-123456";          // type the same token in the app
// ==================================================================

const char*   FW_VERSION         = "1.0.0";
const float   TILT_LIMIT_DEG     = 60.0f;   // PENDING starts above this angle
const unsigned long TILT_HOLD_MS = 300;     // must stay above the limit this long (ignores bumps)
const unsigned long COUNTDOWN_MS = 15000;   // time to cancel
const unsigned long CANCELLED_HOLD_MS = 5000; // CANCELLED stays visible this long
const float   REARM_DEG          = 45.0f;   // after an event, tilt must drop below this to arm again

const uint8_t PIN_SDA    = D2;
const uint8_t PIN_SCL    = D1;
const uint8_t PIN_BUTTON = D5;
const uint8_t PIN_BUZZER = D6;
const uint8_t MPU_ADDR   = 0x68;

enum State { ARMED, PENDING, CANCELLED, CONFIRMED };
State state = ARMED;

String deviceId;
String bootId;
String eventId = "";
uint32_t eventCounter = 0;

unsigned long pendingStart = 0;
unsigned long cancelledAt = 0;
unsigned long overSince = 0;
bool armedReady = true;

// sensor
float fx = 0, fy = 0, fz = 1;           // filtered acceleration (g)
float refx = 0, refy = 0, refz = 1;     // "upright" reference (unit vector)
float tiltDeg = 0;
bool sensorOk = false;
bool filterInit = false;
uint8_t readFailures = 0;
unsigned long lastSample = 0;

// button
bool btnRaw = false, btnStable = false;
unsigned long btnChange = 0;

ESP8266WebServer server(80);

const char* stateName(State s) {
  switch (s) {
    case ARMED: return "ARMED";
    case PENDING: return "PENDING";
    case CANCELLED: return "CANCELLED";
    default: return "CONFIRMED";
  }
}

// ---------------------------------------------------------------- MPU6050
void mpuInit() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x6B);   // PWR_MGMT_1: wake up
  Wire.write(0x00);
  Wire.endTransmission();
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x1A);   // CONFIG: digital low-pass filter ~44 Hz
  Wire.write(0x03);
  Wire.endTransmission();
}

bool readAccel(float &ax, float &ay, float &az) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)6) != 6) return false;
  int16_t x = (Wire.read() << 8) | Wire.read();
  int16_t y = (Wire.read() << 8) | Wire.read();
  int16_t z = (Wire.read() << 8) | Wire.read();
  ax = x / 16384.0f;   // +-2 g range
  ay = y / 16384.0f;
  az = z / 16384.0f;
  return true;
}

void sampleSensor() {
  float ax, ay, az;
  if (!readAccel(ax, ay, az)) {
    if (readFailures < 255) readFailures++;
    if (readFailures > 25) sensorOk = false;
    return;
  }
  readFailures = 0;
  sensorOk = true;
  if (!filterInit) { fx = ax; fy = ay; fz = az; filterInit = true; }
  else { fx = 0.75f * fx + 0.25f * ax; fy = 0.75f * fy + 0.25f * ay; fz = 0.75f * fz + 0.25f * az; }

  float n = sqrtf(fx * fx + fy * fy + fz * fz);
  if (n < 0.3f) return;                       // free fall / bad data: keep last angle
  float d = (fx * refx + fy * refy + fz * refz) / n;
  if (d > 1.0f) d = 1.0f;
  if (d < -1.0f) d = -1.0f;
  tiltDeg = acosf(d) * 57.29578f;
}

// Average ~0.5 s of samples and treat that direction as "upright" (0 degrees).
bool calibrateNow() {
  float sx = 0, sy = 0, sz = 0;
  int got = 0;
  for (int i = 0; i < 25; i++) {
    float ax, ay, az;
    if (readAccel(ax, ay, az)) { sx += ax; sy += ay; sz += az; got++; }
    delay(20);
    yield();
  }
  if (got < 10) return false;
  sx /= got; sy /= got; sz /= got;
  float n = sqrtf(sx * sx + sy * sy + sz * sz);
  if (n < 0.5f) return false;
  refx = sx / n; refy = sy / n; refz = sz / n;
  fx = sx; fy = sy; fz = sz; filterInit = true;
  tiltDeg = 0;
  return true;
}

// ---------------------------------------------------------------- state machine
void startPending(unsigned long now) {
  eventCounter++;
  eventId = bootId + "-" + String(eventCounter);
  pendingStart = now;
  state = PENDING;
  Serial.println("PENDING " + eventId);
}

void doCancel(const char* who) {
  if (state != PENDING) return;
  state = CANCELLED;
  cancelledAt = millis();
  Serial.print("CANCELLED by ");
  Serial.println(who);
}

void backToArmed() {
  state = ARMED;
  eventId = "";
  overSince = 0;
  armedReady = (tiltDeg < REARM_DEG);   // bike still lying down? wait until it is upright again
}

void updateStateMachine(unsigned long now) {
  switch (state) {
    case ARMED:
      if (!armedReady) {
        if (tiltDeg < REARM_DEG) armedReady = true;
        overSince = 0;
      } else if (sensorOk && tiltDeg > TILT_LIMIT_DEG) {
        if (overSince == 0) overSince = now;
        else if (now - overSince >= TILT_HOLD_MS) startPending(now);
      } else {
        overSince = 0;
      }
      break;
    case PENDING:
      if (now - pendingStart >= COUNTDOWN_MS) {
        state = CONFIRMED;
        Serial.println("CONFIRMED " + eventId);
      }
      break;
    case CANCELLED:
      if (now - cancelledAt >= CANCELLED_HOLD_MS) backToArmed();
      break;
    case CONFIRMED:
      break;   // stays until the phone sends /ack
  }
}

void updateButton(unsigned long now) {
  bool pressed = (digitalRead(PIN_BUTTON) == LOW);
  if (pressed != btnRaw) { btnRaw = pressed; btnChange = now; }
  if (now - btnChange >= 40 && btnStable != btnRaw) {
    btnStable = btnRaw;
    if (btnStable) doCancel("handlebar button");
  }
}

void updateOutputs(unsigned long now) {
  bool buzz = false;
  if (state == PENDING) buzz = ((now / 200) % 2) == 0;
  else if (state == CONFIRMED) buzz = ((now / 1000) % 2) == 0;
  digitalWrite(PIN_BUZZER, buzz ? HIGH : LOW);

  bool led;
  if (!sensorOk) led = ((now / 100) % 2) == 0;          // fast blink = sensor problem
  else if (state == ARMED) led = ((now / 1500) % 10) == 0; // short heartbeat
  else led = true;
  digitalWrite(LED_BUILTIN, led ? LOW : HIGH);          // LED is active low
}

// ---------------------------------------------------------------- HTTP
bool authorized() {
  return server.header("Authorization") == (String("Bearer ") + PAIRING_TOKEN);
}

void sendJson(int code, const String& body) {
  server.send(code, "application/json", body);
}

bool rejectIfUnauthorized() {
  if (authorized()) return false;
  sendJson(401, "{\"error\":\"unauthorized\"}");
  return true;
}

String extractJsonString(const String& body, const char* key) {
  String pat = String("\"") + key + "\"";
  int k = body.indexOf(pat);
  if (k < 0) return "";
  int colon = body.indexOf(':', k + pat.length());
  if (colon < 0) return "";
  int q1 = body.indexOf('"', colon + 1);
  if (q1 < 0) return "";
  int q2 = body.indexOf('"', q1 + 1);
  if (q2 < 0) return "";
  return body.substring(q1 + 1, q2);
}

String stateOnlyJson(const char* extraKey = nullptr, const char* extraVal = nullptr) {
  String j = "{\"state\":\"";
  j += stateName(state);
  j += "\"";
  if (extraKey) { j += ",\""; j += extraKey; j += "\":\""; j += extraVal; j += "\""; }
  j += "}";
  return j;
}

void handleStatus() {
  if (rejectIfUnauthorized()) return;
  unsigned long now = millis();
  long remaining = 0;
  if (state == PENDING) {
    unsigned long el = now - pendingStart;
    remaining = (el >= COUNTDOWN_MS) ? 0 : (long)(COUNTDOWN_MS - el);
  }
  String j;
  j.reserve(220);
  j += "{\"deviceId\":\"";  j += deviceId;
  j += "\",\"eventId\":\"";  j += eventId;
  j += "\",\"state\":\"";    j += stateName(state);
  j += "\",\"tiltDeg\":";    j += String(tiltDeg, 1);
  j += ",\"remainingMs\":";  j += String(remaining);
  j += ",\"fw\":\"";         j += FW_VERSION;
  j += "\",\"sensorOk\":";   j += (sensorOk ? "true" : "false");
  j += "}";
  sendJson(200, j);
}

void handleCancel() {
  if (rejectIfUnauthorized()) return;
  String id = extractJsonString(server.arg("plain"), "eventId");
  if (id.length() > 0 && id == eventId) {
    if (state == PENDING) doCancel("app");
    if (state == CANCELLED) {
      String j = "{\"eventId\":\"" + eventId + "\",\"state\":\"CANCELLED\"}";
      sendJson(200, j);
      return;
    }
    if (state == CONFIRMED) {
      sendJson(409, "{\"state\":\"CONFIRMED\",\"error\":\"already_confirmed\"}");
      return;
    }
  }
  sendJson(409, stateOnlyJson("error", "no_such_event"));
}

void handleAck() {
  if (rejectIfUnauthorized()) return;
  String id = extractJsonString(server.arg("plain"), "eventId");
  if (state == CONFIRMED) {
    if (id == eventId) { backToArmed(); sendJson(200, stateOnlyJson()); }
    else sendJson(409, stateOnlyJson("error", "no_such_event"));
    return;
  }
  sendJson(200, stateOnlyJson());   // already acknowledged: idempotent
}

void handleCalibrate() {
  if (rejectIfUnauthorized()) return;
  if (state != ARMED) { sendJson(409, stateOnlyJson("error", "not_armed")); return; }
  if (!calibrateNow()) { sendJson(500, "{\"error\":\"sensor_unavailable\"}"); return; }
  armedReady = true;
  overSince = 0;
  sendJson(200, stateOnlyJson());
}

void handleNotFound() {
  sendJson(404, "{\"error\":\"not_found\"}");
}

// ---------------------------------------------------------------- setup / loop
void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);

  deviceId = "IOTRIX-" + String(ESP.getChipId(), HEX);
  deviceId.toUpperCase();
  bootId = String((uint32_t)RANDOM_REG32, HEX);   // new for every boot, so event ids never repeat

  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(100000);
  mpuInit();
  sensorOk = calibrateNow();   // keep the bike upright while the board starts
  Serial.println(sensorOk ? "MPU6050 OK, calibrated" : "MPU6050 NOT FOUND - check wiring");

  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  const char* wanted[] = {"Authorization"};
  server.collectHeaders(wanted, 1);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/cancel", HTTP_POST, handleCancel);
  server.on("/ack", HTTP_POST, handleAck);
  server.on("/calibrate", HTTP_POST, handleCalibrate);
  server.onNotFound(handleNotFound);
  server.begin();

  Serial.println();
  Serial.println("Device : " + deviceId);
  Serial.println("Token  : " + String(PAIRING_TOKEN));
  Serial.println("Joining Wi-Fi \"" + String(WIFI_SSID) + "\" ...");
}

unsigned long lastWifiPrint = 0;
bool wasConnected = false;

void loop() {
  unsigned long now = millis();

  // The safety logic never depends on Wi-Fi or on the phone.
  if (now - lastSample >= 20) {
    lastSample = now;
    sampleSensor();
  }
  updateButton(now);
  updateStateMachine(now);
  updateOutputs(now);

  server.handleClient();

  bool connected = (WiFi.status() == WL_CONNECTED);
  if (connected && !wasConnected) {
    Serial.println("Wi-Fi connected. Enter this IP in the app: " + WiFi.localIP().toString());
  }
  if (!connected && now - lastWifiPrint > 5000) {
    lastWifiPrint = now;
    Serial.println("Wi-Fi not connected (2.4 GHz hotspot? correct password?)");
  }
  wasConnected = connected;
  yield();
}
