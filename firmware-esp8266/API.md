# IoTrix board API v1 (ESP8266 <-> Android app)

HTTP, port 80, JSON. Every request needs:  `Authorization: Bearer <pairingToken>`  (wrong token -> `401`).
The token is the `PAIRING_TOKEN` constant in the firmware; type the same value in the app (Settings).

The BOARD owns the tilt angle, the 60 degree rule and the 15 s timer. The app never decides any of that.

## States
ARMED -> PENDING -> CANCELLED | CONFIRMED

| From | Event | To |
|---|---|---|
| ARMED | tilt > 60 deg for >= 300 ms (and the board was upright since the last event) | PENDING (new eventId, 15 000 ms) |
| PENDING | handlebar button, or POST /cancel with the current eventId | CANCELLED |
| PENDING | 15 s elapsed | CONFIRMED |
| CANCELLED | 5 s | ARMED (eventId cleared) |
| CONFIRMED | POST /ack with the current eventId | ARMED |

CONFIRMED is never left on its own, so an event that happened while the phone was away is still there
when the phone reconnects. A state change to ARMED without passing through CONFIRMED means "no alert".

## GET /status
```json
{"deviceId":"IOTRIX-A1B2C3","eventId":"7f3a91c2-4","state":"PENDING","tiltDeg":63.2,"remainingMs":11400,"fw":"1.0.0","sensorOk":true}
```
- `eventId`: `<bootId>-<counter>`. `bootId` is random per boot, so ids never repeat after a reset. Empty string while ARMED.
- `remainingMs`: > 0 only in PENDING, starts at 15000.
- `sensorOk` is an extra field; the app ignores unknown fields.

App polling: one request at a time, every 500 ms, timeout 1000 ms. 3 failures in a row = "Disconnected", then retry every 1 s.

## POST /cancel      body `{"eventId":"7f3a91c2-4"}`
| Result | Meaning |
|---|---|
| `200 {"eventId":"...","state":"CANCELLED"}` | Board accepted the cancel (repeating it is also 200) |
| `409 {"state":"CONFIRMED","error":"already_confirmed"}` | Countdown ended first |
| `409 {"state":"<current>","error":"no_such_event"}` | eventId does not match |

The app shows "Cancelled" only after the 200 above. Any other answer or a timeout is shown as a failure.

## POST /ack         body `{"eventId":"7f3a91c2-4"}`
`200 {"state":"ARMED"}`. Sent only after at least one emergency SMS was accepted by the phone's radio.
Repeating it is also 200. `409 no_such_event` if the board is CONFIRMED for a different event.

## POST /calibrate   body `{}`
The current position becomes 0 degrees (average of ~0.5 s). Only allowed in ARMED, otherwise `409 {"error":"not_armed"}`.
The reference is kept in RAM, so the board re-calibrates itself at boot (keep the bike upright when powering on).

## App duplicate protection
The app keeps the list of eventIds whose message it already sent. A CONFIRMED event that is already in that list
is only acknowledged again, never texted again.
