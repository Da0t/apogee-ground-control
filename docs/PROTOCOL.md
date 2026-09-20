# Apogee local protocol v1

Independent simulator TCP server: localhost:7071 by default. Ground connects as a client. UTF-8 JSON objects separated by LF, maximum 16,384 bytes per object. Missing version/type, unsupported versions, truncated and oversized frames close the connection. This is a custom educational protocol.

## Ground → spacecraft

```json
{"version":1,"type":"COMMAND","commandId":"00000000-0000-4000-8000-000000000001","kind":"POWER_ON","durationSeconds":2}
{"version":1,"type":"QUERY","commandId":"00000000-0000-4000-8000-000000000001"}
{"version":1,"type":"FAULT","mode":"DROP_COMPLETION"}
```

Kinds: POWER_ON, CAPTURE, POWER_OFF. UUID required for command identity; reusing an ID with different kind or duration is invalid. `durationSeconds` is 2 for power transitions and 2–20 for collection; omitted/zero uses the legacy defaults (2/6/2). Deploy matching ground/simulator builds when using these arguments. Repeated identical commands return their durable status without repeating side effects.

QUERY is read-only and returns ACCEPTED, COMPLETED, REJECTED, FAILED, or NOT_FOUND. V1 simulator currently produces ACCEPTED/COMPLETED/REJECTED; the ground protocol also understands FAILED.

FAULT is a simulator-only test control: NONE (clear fault and restore battery/storage baseline), DROP_COMPLETION (suppress CAPTURE final reply), STALE_TELEMETRY (suppress telemetry), LOW_BATTERY (15%), REJECT_CAPTURE. It does not erase the ledger. These controls are not flight commands.

## Spacecraft → ground

```json
{"version":1,"type":"ACK","commandId":"00000000-0000-4000-8000-000000000001","status":"COMPLETED","reason":"Verified POWER_ON"}
{"version":1,"type":"TELEMETRY","bootId":"example-session","sequence":3,"tick":3,"battery":82.0,"storage":12.0,"instrument":"READY","mode":"NOMINAL","observations":0,"fault":"NONE"}
```

Telemetry: once per tick unless suppressed. Battery/storage are percentages in [0,100]; the ground service rejects invalid values, invalid instrument states, or out-of-order/repeated sequence numbers from the same boot. Ground adds `receivedAt` in Unix epoch milliseconds. Measurements expire five seconds after receipt.

## HTTP API

| Endpoint | Purpose |
| --- | --- |
| GET /api/health | Ground HTTP process health (not a spacecraft/database readiness assertion) |
| GET /api/state | Current state, recent samples/runs/commands/events |
| GET /api/stream | SSE `state` events; same shape as /state |
| POST /api/runs | Start a saved definition; `requestId` required, optional `procedureId` and `version`. Same ID returns the original run snapshot. Add `notBefore` and `expiresAt` (Unix ms) to schedule it. |
| POST /api/runs/{id}/pause | Stop scheduling new steps; in-flight work continues |
| POST /api/runs/{id}/resume | Continue paused run once outcome is known and telemetry/link are usable |
| POST /api/runs/{id}/abort | Stop future steps; await in-flight resolution before releasing reservation |
| POST /api/runs/{id}/reconcile | Query unresolved command; rate limited to once per second |
| GET /api/runs/{id}/export | Run + command ledger + event trace, marked SIMULATED |
| POST /api/procedures | Publish `{id,baseVersion,name,description,steps}`; rejects a stale base version |
| POST /api/contacts | Save `{enabled,epochMillis,periodSeconds,windowSeconds,station,latitude,longitude}` |
| POST /api/scenario | Body `{"mode":"NONE"}` or another supported fault |
| POST /api/disconnect | Close local TCP link and defer reconnect for 15 seconds |

Action conflicts return 409; invalid inputs return 400; browser cross-origin mutations return 403. No public authentication or authorization model is implemented. Bind/publish only to localhost.

`/api/state` also includes saved `procedures`, pending `scheduled` runs, and `contacts` (saved plan, open state, cycle phase and five upcoming windows). A procedure step contains `kind`, `durationSeconds`, `timeoutSeconds`, `minBattery` and `maxStorage`. Command records include `stepIndex` so repeated collection commands remain distinct. Exported runs include the immutable `definition` they executed. The UI routes `/console`, `/procedures`, `/contacts` and `/history` all serve the application entry point.
