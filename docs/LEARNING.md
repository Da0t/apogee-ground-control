# Explain the project yourself

## Portfolio story

“My earlier work involved interpreting sensor streams and building distributed communication. I built Apogee to study the next problem: executing and verifying a remote action when acknowledgments disappear or a service restarts.”

Use **spacecraft ground-control simulator**, not flight software, operational satellite controller, or certified C2 system. This version uses illustrative telemetry. The network, database and process failures are real software behavior.

## Read the code in this order

1. `domain/Models.java`: command/run states and pure precondition checks. Explain why UNKNOWN is necessary.
2. `simulator/Spacecraft.java`: a tick-based model, resource changes, command IDs and atomic snapshots.
3. `protocol/Wire.java`: framing a stream of bytes into bounded messages.
4. `domain/GroundEngine.java`: persist, dispatch, verify, time out and reconcile.
5. `ground/PostgresStore.java`: transaction boundaries, relational keys and JSONB documents.
6. `ground/MissionApi.java` and the React console: expose authoritative backend state.
7. The tests: compare a requirement with a failure scenario and its assertion.

## Questions to be able to answer

- Why is a TCP acknowledgment different from a spacecraft command-completion acknowledgment?
- What happens if the ground process dies immediately before or immediately after writing a command?
- Why can't the database transaction make the remote execution atomic?
- Why can a paused or aborted procedure still have a command executing?
- What happens if the simulator loses its entire ledger? Which guarantee no longer holds?
- How does Java's type system help restrict valid commands, and where is runtime validation still needed?
- Why use SSE for this console rather than WebSockets?
- Why does this prototype use one synchronized writer? What would need to change for multiple ground instances?
- Why does a deterministic simulator not imply a real-time/physically accurate spacecraft model?

## Development checkpoints

Run nominal behavior first. Then suppress completion and explain every state transition before reading the implementation. Change a precondition and add a meaningful test. Restart the backend during a command and explain the preserved uncertainty. Finally trace a command from the button through the API, database, TCP frame, simulator ledger and return acknowledgment.

The core value of Java here is typed domain modeling, encapsulation, library support, straightforward tests and a mature application ecosystem. Spring handles HTTP, configuration and persistence wiring; it does not decide the mission rules.

## Résumé phrasing after verification

- Built a Java/Spring Boot spacecraft ground-control simulator with telemetry preconditions, staged command verification and PostgreSQL execution history.
- Implemented reconciliation for uncertain command outcomes and durable simulator deduplication; tested communication faults and process-restart recovery.
- Developed a React/TypeScript operator console with live telemetry, procedure controls and exportable event history.

Only add performance numbers after a documented benchmark. The project currently makes no throughput or recovery-latency claims. Keep C++ off this project's stack. Future scheduler integration should be described only after it exists.
