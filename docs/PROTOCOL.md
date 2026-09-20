# API and protocol reference

[Project overview](../README.md) · [Operator guide](OPERATING.md) · [Architecture](ARCHITECTURE.md)

Apogee uses two communication paths: HTTP between browser and ground, and TCP between ground and spacecraft. This is a custom educational protocol; no CCSDS or XTCE compatibility is claimed.

## HTTP: browser and ground

Default base URL: `http://127.0.0.1:8081`. Requests with a body use `Content-Type: application/json`.

### Read state and history

| Method and path             | Response                                                                                                                                            |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /api/health`           | Ground HTTP process health; does not establish database or spacecraft readiness.                                                                    |
| `GET /api/state`            | Current ground snapshot: link/freshness, telemetry, current run/commands, recent events/samples/runs, definitions, contacts, and pending schedules. |
| `GET /api/stream`           | SSE events named `state`, with the same snapshot shape; sent on connection and about once a second.                                                 |
| `GET /api/runs/{id}/export` | Run including its saved definition, commands, up to 250 events, and `dataSource: "SIMULATED"`.                                                      |

The frontend uses `EventSource` for SSE. There is no WebSocket endpoint. `/console`, `/procedures`, `/contacts`, and `/history` serve the same application entry point.

### Change mission state

| Method and path                 | Behavior                                                                                                                               |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /api/runs`                | Create an immediate or scheduled execution of a saved definition.                                                                      |
| `POST /api/runs/{id}/pause`     | Stop future steps; accepted spacecraft work continues.                                                                                 |
| `POST /api/runs/{id}/resume`    | Resume with permitted contact, live link, and fresh telemetry. An `UNKNOWN` command requires reconciliation first.                     |
| `POST /api/runs/{id}/abort`     | Stop future steps. Unresolved work retains its reservation until reconciled; a pending schedule is cancelled immediately.              |
| `POST /api/runs/{id}/reconcile` | Query the unresolved command; rate limited to once per second by the engine.                                                           |
| `POST /api/procedures`          | Publish a definition using its base version to detect a stale edit.                                                                    |
| `POST /api/contacts`            | Save a repeating contact policy. Enabling/changing windows requires no active run; continuous contact can be restored during recovery. |
| `POST /api/scenario`            | Apply a simulator fault using a body such as `{"mode":"NONE"}`.                                                                        |
| `POST /api/disconnect`          | Close the ground TCP connection and defer reconnect for 15 seconds.                                                                    |

Action endpoints without fields can receive `{}`. Mission validation errors return `400`, state conflicts return `409`, and cross-site browser mutations return `403`. These local-demo checks are not a user authentication system.

### Run request

```json
{
  "requestId": "00000000-0000-4000-8000-000000000001",
  "procedureId": "OBSERVATION-001",
  "version": 1
}
```

- Generate a new UUID for each intentional execution. Repeating an existing `requestId` returns the existing run in its current saved state, without creating another one.
- Omitting `procedureId` selects `OBSERVATION-001`; omitting `version` selects the latest saved version, with built-in observation v1 as the default fallback.
- Add `notBefore` and `expiresAt`, both Unix epoch milliseconds, to schedule a run. Both are required for scheduling. The latter is a **start** deadline.
- The API permits earliest starts within seven days and deadlines at most 24 hours after earliest start. A five-second tolerance permits a start timestamp just in the past. There may be at most 50 pending runs.

Runs preserve `definition`, `procedure`, `version`, and their execution state. Commands include `stepIndex` so repeated command kinds remain distinct.

### Procedure request

```json
{
  "id": "SURVEY-001",
  "baseVersion": 0,
  "name": "Short observation",
  "description": "Collect once and return the instrument to standby.",
  "steps": [
    {
      "kind": "POWER_ON",
      "durationSeconds": 2,
      "timeoutSeconds": 12,
      "minBattery": 30,
      "maxStorage": 80
    },
    {
      "kind": "CAPTURE",
      "durationSeconds": 6,
      "timeoutSeconds": 12,
      "minBattery": 30,
      "maxStorage": 80
    },
    {
      "kind": "POWER_OFF",
      "durationSeconds": 2,
      "timeoutSeconds": 12,
      "minBattery": 30,
      "maxStorage": 80
    }
  ]
}
```

`baseVersion: 0` creates a new ID. A revision supplies the latest version known when editing began; the server creates `baseVersion + 1`. Published versions cannot be overwritten.

| Field             | Validation / meaning                                                                                              |
| ----------------- | ----------------------------------------------------------------------------------------------------------------- |
| `id`              | 3–40 uppercase letters, digits, underscores, or hyphens; first character is a letter.                             |
| `steps`           | 2–8 typed commands, valid power ordering, final instrument state off.                                             |
| `durationSeconds` | Two ticks for power transitions; 2–20 ticks for collection.                                                       |
| `timeoutSeconds`  | At least duration + 2, at most 120. Measured from dispatch and restarted when a new acceptance reply is recorded. |
| `minBattery`      | 30–100%; checked for power-on and collection. Power-off does not require this battery threshold.                  |
| `maxStorage`      | 0–80%; checked for collection.                                                                                    |

See [Models.java](../src/main/java/dev/datnguyen/apogee/domain/Models.java) for definition and telemetry validation.

### Contact request

Fields: `enabled`, `epochMillis`, `periodSeconds`, `windowSeconds`, `station`, `latitude`, and `longitude`.

The epoch is the first window's start in Unix milliseconds. Cycles last 30–600 seconds; contact lasts at least five seconds and leaves at least ten seconds of blackout. Latitude is limited to ±85° and longitude to ±180°. The epoch must be nonnegative and no more than 24 hours in the future.

`enabled: false` restores continuous contact. Coordinates affect the schematic only. Responses include the saved plan, `open`, `phase`, `nextOpen`, `nextClose`, and five upcoming windows. When disabled, window timing is preview data rather than an enforced link policy.

## TCP: ground and spacecraft

The ground service is the client. The simulator listens on port 7071 by default: loopback for local Java, or internal `simulator:7071` in Compose.

[Wire.java](../src/main/java/dev/datnguyen/apogee/protocol/Wire.java) implements UTF-8 JSON objects separated by LF, bounded to 16,384 bytes per object. Missing/invalid envelope fields, unsupported versions, oversized frames, or truncated frames close the connection. TCP packet boundaries are not application-message boundaries.

### Ground-to-spacecraft messages

Each line below is one complete frame, followed by a newline:

```jsonl
{"version":1,"type":"COMMAND","commandId":"00000000-0000-4000-8000-000000000001","kind":"POWER_ON","durationSeconds":2}
{"version":1,"type":"QUERY","commandId":"00000000-0000-4000-8000-000000000001"}
{"version":1,"type":"FAULT","mode":"DROP_COMPLETION"}
```

| Type      | Meaning                                                                                                                                                                               |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `COMMAND` | Execute `POWER_ON`, `CAPTURE`, or `POWER_OFF`. A UUID identifies the operation. Reusing it with the same kind/duration returns the recorded result; different arguments are rejected. |
| `QUERY`   | Read the saved result for an existing command ID without executing it again.                                                                                                          |
| `FAULT`   | Simulator-only test control: `NONE`, `DROP_COMPLETION`, `STALE_TELEMETRY`, `LOW_BATTERY`, or `REJECT_CAPTURE`.                                                                        |

Command durations match the procedure rules above. An omitted/zero duration uses legacy defaults of 2/6/2 ticks. Deploy matching ground and simulator builds when using duration arguments. Duplicate suppression depends on preserving the simulator ledger.

### Spacecraft-to-ground messages

```jsonl
{"version":1,"type":"ACK","commandId":"00000000-0000-4000-8000-000000000001","status":"COMPLETED","reason":"Verified POWER_ON"}
{"version":1,"type":"TELEMETRY","bootId":"example-session","sequence":3,"tick":3,"battery":82.0,"storage":12.0,"instrument":"READY","mode":"NOMINAL","observations":0,"fault":"NONE"}
```

Acknowledgments contain `ACCEPTED`, `COMPLETED`, or `REJECTED` from the current simulator; the ground also understands `FAILED`. A query for an absent ID returns `NOT_FOUND`, which preserves ground-side uncertainty.

Telemetry is emitted once per tick unless suppressed. The ground checks battery/storage ranges, instrument state, and increasing sequence numbers within a boot. It adds `receivedAt` in epoch milliseconds. Freshness is at most five seconds since receipt of a new accepted measurement, and the active contact policy also gates its use.

`NONE` clears the injected fault and restores battery/storage baseline values without erasing the ledger or cancelling work. For scenario behavior and recovery steps, see the [operator guide](OPERATING.md#failure-scenarios).
