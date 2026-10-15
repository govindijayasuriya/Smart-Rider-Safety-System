// Bench integration firmware. Accelerometer-only tilt; not validated for riding.
#include <Arduino.h>
#include <Wire.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <math.h>
#include "secrets.h"

// EDIT these three values. Use the same token in the Android app Settings.
constexpr uint8_t SENSOR = 0x68;
constexpr uint32_t WINDOW_MS = 15000, HOLD_MS = 300, REARM_MS = 2000;
ESP8266WebServer server(80);
String state = "UNCALIBRATED", eventId, deviceId, bootId;
uint32_t eventCount = 0, pendingAt = 0, aboveAt = 0, uprightAt = 0;
uint32_t sampleAt = 0, printAt = 0;
bool above = false, upright = false, calibrated = false, sensorOk = false;
bool acknowledged = false, fsOk = false;
float rx = 0, ry = 0, rz = 1, tilt = 0;

bool writeReg(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(SENSOR); Wire.write(reg); Wire.write(value);
  return Wire.endTransmission() == 0;
}
int16_t signed16() {
  uint16_t h = Wire.read(), l = Wire.read();
  return (int16_t)((h << 8) | l);
}
bool acceleration(float &x, float &y, float &z) {
  Wire.beginTransmission(SENSOR); Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(SENSOR, (uint8_t)6) != 6) {
    while (Wire.available()) Wire.read();
    return false;
  }
  x = signed16()/16384.0f; y = signed16()/16384.0f; z = signed16()/16384.0f;
  float n = sqrtf(x*x+y*y+z*z);
  return isfinite(n) && n > 0.1f;
}
bool persistEvent() {
  if (!fsOk) return false;
  JsonDocument d; d["state"] = state; d["eventId"] = eventId;
  File f = LittleFS.open("/event.tmp", "w");
  if (!f) return false;
  size_t n = serializeJson(d, f); f.close();
  return n > 0 && LittleFS.rename("/event.tmp", "/event.json");
}
void error(int code, const char* message) {
  JsonDocument d; d["error"] = message; d["state"] = state;
  String body; serializeJson(d, body); server.send(code, "application/json", body);
}
bool authorized() {
  if (server.header("Authorization") == String("Bearer ") + PAIRING_TOKEN) return true;
  error(401, "unauthorized"); return false;
}
void updateDeadline() {
  if (state == "PENDING" && (uint32_t)(millis()-pendingAt) >= WINDOW_MS) {
    state = "CONFIRMED"; acknowledged = false;
    if (!persistEvent()) Serial.println("WARNING: event persistence failed");
    Serial.println("CONFIRMED - app may send SMS");
  }
}
void sendStatus() {
  updateDeadline();
  JsonDocument d;
  d["deviceId"] = deviceId; d["eventId"] = eventId;
  d["state"] = state; d["tiltDeg"] = tilt;
  uint32_t elapsed = (uint32_t)(millis()-pendingAt);
  d["remainingMs"] = state == "PENDING" && elapsed < WINDOW_MS ? WINDOW_MS-elapsed : 0;
  d["fw"] = "iotrix-bench-1.0"; d["sensorOk"] = sensorOk;
  d["calibrated"] = calibrated; d["storageOk"] = fsOk;
  String body; serializeJson(d, body);
  server.send(200, "application/json", body);
}
bool matchingEvent() {
  JsonDocument d;
  if (deserializeJson(d, server.arg("plain"))) { error(400,"invalid_json"); return false; }
  String id = d["eventId"] | "";
  if (id.isEmpty() || id != eventId) { error(409,"event_mismatch"); return false; }
  return true;
}
bool calibrate() {
  float sx=0, sy=0, sz=0;
  // Called only while no pending/confirmed event is active.
  for (int i=0; i<100; i++) {
    float x,y,z;
    if (!acceleration(x,y,z)) { sensorOk=false; return false; }
    float n = sqrtf(x*x+y*y+z*z);
    if (n < 0.75f || n > 1.25f) return false;
    sx+=x; sy+=y; sz+=z; delay(10);
  }
  float n=sqrtf(sx*sx+sy*sy+sz*sz);
  if (n < 1) return false;
  rx=sx/n; ry=sy/n; rz=sz/n;
  calibrated=true; sensorOk=true; tilt=0; above=false; upright=false;
  state="ARMED"; eventId=""; acknowledged=false;
  return true;
}
void sample() {
  float x,y,z;
  sensorOk=acceleration(x,y,z);
  if (!sensorOk) { above=false; upright=false; return; }
  if (!calibrated) return;
  float n=sqrtf(x*x+y*y+z*z);
  tilt=acosf(constrain((x*rx+y*ry+z*rz)/n,-1.0f,1.0f))*180.0f/PI;
  uint32_t now=millis();
  if (state=="ARMED") {
    if (tilt>60) {
      if (!above) { above=true; aboveAt=now; }
      if ((uint32_t)(now-aboveAt)>=HOLD_MS) {
        eventId=bootId+"-"+String(++eventCount);
        state="PENDING"; pendingAt=now; above=false; upright=false;
        if (!persistEvent()) Serial.println("WARNING: event persistence failed");
        Serial.println("PENDING - 15 second cancel window");
      }
    } else above=false;
  }
  if (state=="CANCELLED" || (state=="CONFIRMED" && acknowledged)) {
    if (tilt<20) {
      if (!upright) { upright=true; uprightAt=now; }
      if ((uint32_t)(now-uprightAt)>=REARM_MS) {
        state="ARMED"; eventId=""; upright=false; above=false;
      }
    } else upright=false;
  }
}
void setup() {
  Serial.begin(115200); Wire.begin(4,5); Wire.setClock(100000);
  delay(300);
  bool configured=writeReg(0x6B,0); delay(100);
  configured=writeReg(0x6C,0)&&configured;
  configured=writeReg(0x1C,0)&&configured;
  Serial.println(configured?"Sensor configured":"Sensor configuration failed - check wiring");
  deviceId="bike-"+String(ESP.getChipId(),HEX);
  fsOk=LittleFS.begin();
  // Sequence persisted once per boot gives event IDs that do not repeat on reset.
  uint32_t bootSequence=0;
  if (fsOk) {
    File f=LittleFS.open("/boot.txt","r");
    if(f) { bootSequence=f.parseInt(); f.close(); }
    f=LittleFS.open("/boot.txt","w");
    if(f) { f.print(++bootSequence); f.close(); } else fsOk=false;
    f=LittleFS.open("/event.json","r");
    if(f) {
      JsonDocument d;
      if(!deserializeJson(d,f)) {
        eventId=String(d["eventId"] | "");
        if(!eventId.isEmpty()) state="CONFIRMED";
      }
      f.close();
    }
  }
  bootId=deviceId+"-"+String(bootSequence)+"-"+String(micros(),HEX);
  WiFi.persistent(false); WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true); WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
  server.collectHeaders("Authorization");
  server.on("/status",HTTP_GET,[](){if(authorized()) sendStatus();});
  server.on("/cancel",HTTP_POST,[](){
    if(!authorized()) return;
    updateDeadline();
    if(!matchingEvent()) return;
    if(state=="CONFIRMED") { error(409,"too_late"); return; }
    if(state!="PENDING" && state!="CANCELLED") { error(409,"not_pending"); return; }
    // Persist removal BEFORE acknowledging cancellation to the phone.
    if(fsOk && LittleFS.exists("/event.json") && !LittleFS.remove("/event.json")) {
      error(503,"storage_error"); return;
    }
    state="CANCELLED"; upright=false; sendStatus();
  });
  server.on("/ack",HTTP_POST,[](){
    if(!authorized()) return;
    updateDeadline();
    if(!matchingEvent()) return;
    if(state!="CONFIRMED") { error(409,"not_confirmed"); return; }
    if(fsOk && LittleFS.exists("/event.json") && !LittleFS.remove("/event.json")) {
      error(503,"storage_error"); return;
    }
    acknowledged=true; upright=false;
    // A recovered event can be acknowledged before recalibration.
    if(!calibrated) { state="UNCALIBRATED"; eventId=""; }
    sendStatus();
  });
  server.on("/calibrate",HTTP_POST,[](){
    if(!authorized()) return;
    updateDeadline();
    if(state=="PENDING" || state=="CONFIRMED") { error(409,"not_armed"); return; }
    if(!calibrate()) { error(503,"keep_still_and_check_sensor"); return; }
    sendStatus();
  });
  server.onNotFound([](){error(404,"not_found");}); server.begin();
  Serial.println("Waiting for WiFi. Calibrate in the app before each power-up test.");
}
void loop() {
  updateDeadline();
  server.handleClient();
  if((uint32_t)(millis()-sampleAt)>=20) { sampleAt=millis(); sample(); }
  if((uint32_t)(millis()-printAt)>=2000) {
    printAt=millis();
    if(WiFi.status()==WL_CONNECTED) {
      Serial.print("Board IP: "); Serial.print(WiFi.localIP());
    } else Serial.print("WiFi disconnected - check hotspot settings");
    Serial.print(" | "); Serial.print(state);
    Serial.print(" | tilt: "); Serial.print(tilt,1);
    Serial.print(" | sensor: "); Serial.println(sensorOk?"OK":"ERROR");
  }
  yield();
}
