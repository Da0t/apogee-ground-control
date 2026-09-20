# Code walkthrough and learning guide

[Project overview](../README.md) · [Operator guide](OPERATING.md) · [Architecture](ARCHITECTURE.md)

Start here to connect the interface to the Java code. For the exact buttons and demonstrations, use the operator guide alongside the app.

## The four parts

| Part                       | Responsibility                                                                                |
| -------------------------- | --------------------------------------------------------------------------------------------- |
| React in the browser       | Collects operator input and displays backend state.                                           |
| Spring Boot ground service | Decides which steps may run, sends commands, and records evidence.                            |
| PostgreSQL                 | Preserves ground-side procedures, runs, commands, events, telemetry, and contact settings.    |
| Independent Java simulator | Executes accepted work, generates illustrative telemetry, and saves its own command outcomes. |

Docker runs the last three as separate services. The ground service serves the built React assets. The spacecraft can continue working when the browser closes or its ground connection drops.

## Follow one click through the code

The example is **Execute observation** on Mission console.

1. **Create the HTTP request.** In [main.tsx](../web/src/main.tsx), the button calls `post("/runs", ...)` with a unique `requestId` and selected procedure/version. The helper sends JSON to `POST /api/runs`.
2. **Enter Java.** [MissionApi.start()](../src/main/java/dev/datnguyen/apogee/ground/MissionApi.java) receives a `StartRequest` and calls `GroundEngine.start()`. Spring converts the request body into the Java record.
3. **Authorize and save the run.** [GroundEngine.start()](../src/main/java/dev/datnguyen/apogee/domain/GroundEngine.java) loads the saved version, checks contact/link/telemetry requirements and the instrument reservation, then persists a run containing that definition. Reusing a request ID returns the existing run.
4. **Advance the procedure.** `MissionApi.tick()` calls `GroundEngine.tick()` with a 250 ms fixed delay. The engine checks the next step's requirements, saves its command ID and dispatch state, then calls the link.
5. **Transmit bytes.** [TcpLink](../src/main/java/dev/datnguyen/apogee/ground/TcpLink.java) writes through [Wire](../src/main/java/dev/datnguyen/apogee/protocol/Wire.java). Each message is JSON followed by a newline. TCP may divide or combine bytes across packets; the newline lets the receiver reconstruct application messages.
6. **Accept and execute onboard.** [SimulatorMain](../src/main/java/dev/datnguyen/apogee/simulator/SimulatorMain.java) routes a `COMMAND` message to [Spacecraft.command()](../src/main/java/dev/datnguyen/apogee/simulator/Spacecraft.java). The model validates it, saves acceptance, and responds. `Spacecraft.tick()` later applies the effect and saves completion before replying.
7. **Record evidence and move forward.** `GroundEngine.receive()` matches the acknowledgment to its command ID and updates PostgreSQL. A later engine tick advances a successfully completed step. The engine waits for a known outcome before advancing.
8. **Update the browser.** `MissionApi.publish()` sends a state snapshot through `/api/stream` about once a second. React's `EventSource` receives it and updates the interface. Initial page loading also uses `GET /api/state`.

The default steps are power on, collect, and power off. A collection increments an observation counter and storage usage in the model; no camera pixels are generated. The [protocol reference](PROTOCOL.md) shows the actual message fields.

## Understand the failure case

Imagine the simulator completes collection and saves its result, but the completion reply never reaches the ground service.

- The ground service knows it sent a command and may know it was accepted. It cannot infer completion from silence.
- After the verification timeout, the command becomes `UNKNOWN` and the run pauses.
- **Reconcile** sends `QUERY` with the original command ID. The spacecraft returns its saved status.
- **Resume** is an explicit operator decision after recovery; it continues the procedure without creating another collection command for the completed step.

There are two separate histories: what the ground has verified and what the spacecraft has executed. Querying brings them back into agreement. If the spacecraft loses its ledger, that evidence may be unavailable; the software does not promise unconditional exactly-once execution.

## Code map

| File                                                                                                                                                                          | What to look for                                                                                      |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [Application.java](../src/main/java/dev/datnguyen/apogee/Application.java)                                                                                                    | Startup, dependency wiring, recovery before connecting, and the `--simulator` entry path.             |
| [Models.java](../src/main/java/dev/datnguyen/apogee/domain/Models.java)                                                                                                       | Command/run enums, procedure and telemetry records, freshness checks, and step validation.            |
| [GroundEngine.java](../src/main/java/dev/datnguyen/apogee/domain/GroundEngine.java)                                                                                           | `start`, `tick`, `receive`, `action`, `reconcile`, and `recover`: the mission rules.                  |
| [MissionStore.java](../src/main/java/dev/datnguyen/apogee/domain/MissionStore.java) / [PostgresStore.java](../src/main/java/dev/datnguyen/apogee/ground/PostgresStore.java)   | The storage interface and its SQL/transaction implementation.                                         |
| [TcpLink.java](../src/main/java/dev/datnguyen/apogee/ground/TcpLink.java) / [Wire.java](../src/main/java/dev/datnguyen/apogee/protocol/Wire.java)                             | Reconnect behavior, socket writes, message framing, and the 16 KiB limit.                             |
| [SimulatorMain.java](../src/main/java/dev/datnguyen/apogee/simulator/SimulatorMain.java) / [Spacecraft.java](../src/main/java/dev/datnguyen/apogee/simulator/Spacecraft.java) | The TCP server, one-second ticks, independent checks, resource effects, and atomic snapshot saves.    |
| [Contacts.java](../src/main/java/dev/datnguyen/apogee/domain/Contacts.java)                                                                                                   | Repeating time windows and the phase used by the schematic globe.                                     |
| [MissionApi.java](../src/main/java/dev/datnguyen/apogee/ground/MissionApi.java)                                                                                               | HTTP routes, scheduled callbacks, SSE clients, and error responses.                                   |
| [Pages.java](../src/main/java/dev/datnguyen/apogee/ground/Pages.java) / [LocalOriginFilter.java](../src/main/java/dev/datnguyen/apogee/ground/LocalOriginFilter.java)         | Direct page loading and cross-site browser mutation rejection. The filter is not user authentication. |
| [main.tsx](../web/src/main.tsx)                                                                                                                                               | Shared navigation, REST helper, SSE subscription, telemetry display, and event replay.                |
| [ProceduresPage.tsx](../web/src/ProceduresPage.tsx)                                                                                                                           | Draft edits, step ordering, saved-version selection, and publication conflict handling.               |
| [ContactsPage.tsx](../web/src/ContactsPage.tsx) / [Globe.tsx](../web/src/Globe.tsx)                                                                                           | Scheduling/contact forms and an interactive SVG projection of the synthetic track.                    |
| [types.ts](../web/src/types.ts) / [style.css](../web/src/style.css)                                                                                                           | Frontend data shapes and the responsive black-and-white presentation.                                 |
| [Database migrations](../src/main/resources/db/migration)                                                                                                                     | Tables, keys, and the one-active-run constraint applied by Flyway.                                    |
| [Dockerfile](../Dockerfile) / [compose.yaml](../compose.yaml)                                                                                                                 | Building frontend assets into the Java JAR and running the three services.                            |

## Why Java and Spring fit this implementation

| Choice                        | Benefit in this code                                                                             |
| ----------------------------- | ------------------------------------------------------------------------------------------------ |
| Enums                         | Restrict command kinds and statuses to known values. Incoming JSON still needs validation.       |
| Records and copied step lists | Keep published definitions and telemetry values explicit and immutable.                          |
| Interfaces                    | Let the engine use a real PostgreSQL store/TCP link or controlled test implementations.          |
| Injected `Clock`              | Lets tests advance time directly instead of sleeping.                                            |
| `synchronized` engine methods | Serialize API actions, timer checks, and received messages within one ground process.            |
| Virtual threads               | Handle blocking socket reads without embedding that networking loop in the mission logic.        |
| Spring Boot                   | Supplies HTTP handling, JSON conversion, configuration, scheduling, and dependency wiring.       |
| JDBC and transactions         | Make SQL and database commit boundaries visible. The app uses `JdbcTemplate`, not JPA/Hibernate. |

The mission rules are plain Java in `GroundEngine`. Spring connects those rules to HTTP, timers, and persistence. This separation makes the behavior easier to test and explain.

## Questions to practice

1. Why do `SENT`, `ACCEPTED`, and `COMPLETED` mean different things?
2. What survives a backend crash immediately before or after a socket write?
3. Why can't a PostgreSQL transaction make remote execution atomic?
4. Why can a paused or aborted procedure still have work executing onboard?
5. What does the spacecraft ledger prove, and what happens if it is lost?
6. Why does a scheduled run keep its own procedure definition but wait to reserve the instrument?
7. Why use REST plus SSE for this interface? Where does TCP enter the design?
8. What would need to change to run more than one ground-service instance?
9. Which parts of the globe and telemetry are illustrative?

Read the code in this order: **Models → Spacecraft → Wire → GroundEngine → PostgresStore → MissionApi → React**. Then choose a requirement in [verification](VERIFICATION.md) and find the test that demonstrates it.

## Describe the project accurately

A useful interview explanation is: “I built a Java ground-control simulator to study how a remote action can be executed and verified when acknowledgments disappear or a service restarts.”

Possible résumé bullets, after reviewing the implementation and its tests:

- Built a Java/Spring Boot spacecraft ground-control simulator with versioned procedures, telemetry requirements, and PostgreSQL execution history.
- Implemented reconciliation for uncertain command outcomes and persistent command deduplication; tested communication failures and process-restart recovery.
- Developed a React/TypeScript operator console with live telemetry, simulated contact scheduling, and exportable event history.

Use the scope “spacecraft ground-control simulator.” The project uses synthetic spacecraft data, has no C++ flight component, and does not establish performance, flight qualification, or operational satellite-control claims. Its internal contact planner is separate from the original mission-scheduler repository. Add performance numbers only after a documented benchmark.
