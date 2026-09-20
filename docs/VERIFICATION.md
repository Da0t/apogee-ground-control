# Requirements and verification

[Project overview](../README.md) · [Local development](DEVELOPMENT.md) · [Operator guide](OPERATING.md)

The current suite contains **32 Java tests and six browser scenarios**. Results for each pushed commit are available in [GitHub Actions](https://github.com/Da0t/apogee-ground-control/actions/workflows/verify.yml).

## Run the checks

Java unit and PostgreSQL integration tests require a JDK and a running Docker engine:

```sh
./mvnw verify
```

Testcontainers creates its own PostgreSQL database. It does not alter the demo database, and tests fail rather than silently skipping database coverage when Docker is unavailable. See the [Colima configuration](DEVELOPMENT.md#configuration) if needed.

Browser tests require the app at `http://127.0.0.1:8081`, continuous contact, and no active or pending runs:

```sh
npm --prefix web ci
cd web
npx playwright install chromium
npm run test:e2e
```

They create procedures and run history, inject faults, and change contact settings in that app. Use a disposable test environment when preserving a demonstration dataset. `BASE_URL` can target a separately configured test stack; changing the Compose project name alone does not change its ports.

## Coverage by layer

| Layer                   | Tests                                                        | Evidence                                                                                                                 |
| ----------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| Ground execution        | 11 in [GroundEngineTest][engine]                             | Preconditions, reservation, acceptance/completion, uncertainty, abort, reconciliation, and restart.                      |
| Procedures and contacts | 10 in [PlanningTest][planning]                               | Immutable definitions, guards, schedules, window boundaries, deadlines, recovery, and repeated step identities.          |
| Spacecraft              | 6 in [SpacecraftTest][spacecraft]                            | Independent validation, saved outcomes, duplicate suppression, restart, and duration identity.                           |
| TCP framing             | 2 in [WireTest][wire]                                        | Multiple messages, size limits, malformed and truncated input.                                                           |
| PostgreSQL              | 3 in [PostgresIntegrationTest][database]                     | Recovery, transaction rollback, active-run uniqueness, immutable versions, and saved contact policy.                     |
| Browser                 | 6 across [console][console] and [planning][browser-planning] | Actual REST/SSE/TCP flow, lost completion, contact blackout, version conflicts, exports, navigation, and mobile layouts. |

Unit tests use an injected clock and explicit simulator ticks. Browser scenarios use real processes and bounded polling of observed state.

## Requirement trace

| ID  | Requirement                                                                               | Evidence                                                                                                                                            |
| --- | ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1  | Stale or out-of-order telemetry cannot authorize a command                                | [Engine][engine] freshness/sequence tests; [browser][console] stale scenario.                                                                       |
| R2  | Acceptance differs from completion                                                        | [Engine][engine] `acceptedIsNotCompleted`.                                                                                                          |
| R3  | Missing completion produces `UNKNOWN` and pause                                           | [Engine][engine] lost-completion test; [browser][console] real TCP scenario.                                                                        |
| R4  | Recovery does not blindly reissue unresolved commands                                     | [Engine][engine] restart test; [PostgreSQL][database] recovery; separate process-crash check below.                                                 |
| R5  | Duplicate IDs do not repeat an observation while the ledger survives                      | [Spacecraft][spacecraft] duplicate/restart test.                                                                                                    |
| R6  | One active run reserves the instrument                                                    | [Engine][engine] reservation; [PostgreSQL][database] unique-index rollback.                                                                         |
| R7  | Abort stops future steps while retaining unresolved work                                  | [Engine][engine] `abortWaitsForInflightAndPreservesReservation`.                                                                                    |
| R8  | Terminal outcomes cannot regress                                                          | [Engine][engine] `terminalOutcomeCannotRegress`.                                                                                                    |
| R9  | Frames are bounded and validated                                                          | [WireTest][wire].                                                                                                                                   |
| R10 | Reconciliation does not add another observation                                           | [Browser][console] observation-count assertions.                                                                                                    |
| R11 | The spacecraft validates commands independently                                           | [Spacecraft][spacecraft] unsafe-command rejection.                                                                                                  |
| R12 | Operators can inspect recorded decisions                                                  | [Browser][console] export and event-replay assertions.                                                                                              |
| R13 | Revisions cannot mutate existing executions                                               | [Planning][planning] snapshot/publication checks; [PostgreSQL][database] immutable key; [browser][browser-planning] queued export and stale editor. |
| R14 | Scheduled activation requires time, contact, fresh telemetry, and an available instrument | [Planning][planning] activation checks; [browser][browser-planning] contact-loss execution.                                                         |
| R15 | A missed start deadline sends no commands                                                 | [Planning][planning] `missedStartDeadlineNeverDispatches`.                                                                                          |
| R16 | Repeated command kinds retain independent identities and arguments                        | [Planning][planning] repeated collections; [spacecraft][spacecraft] duration persistence.                                                           |
| R17 | Page URLs, refresh, navigation, and mobile layouts work                                   | [Browser][browser-planning] route and globe checks.                                                                                                 |

## Separate process-crash check

Against the default Docker Compose deployment, with continuous contact, no active/pending runs, and the default observation procedure unchanged:

```sh
python3 scripts/check-restart.py
```

The script refuses to interrupt an already active procedure. It starts an observation, sends `SIGKILL` to the ground container during collection, restarts ground, reconciles the outcome, and resumes. Its assertions require one added observation and three commands. A successful run prints a JSON result with `PASS`.

This local Docker check has passed separately; it is not part of the GitHub Actions browser job and does not verify cloud recovery.

## Continuous integration and optional AWS checks

The [workflow](../.github/workflows/verify.yml) runs Java on JDK 21 and browser scenarios on Linux. It uploads Java reports, browser screenshots, and service logs as verification artifacts. Screenshots show synthetic spacecraft data.

The workflow also validates the optional CloudFormation template, runs six offline deployment-helper tests, and exercises the real JAR against disposable local PostgreSQL with TLS. The TLS check covers role permissions, repeatable initialization, Flyway, encrypted JDBC, config-tree credentials, and rejection of a mismatched server hostname.

These checks use no AWS credentials or paid cloud resources. An AWS deployment, SSM access path, regional behavior, and RDS restore have not been verified in an account. See the [AWS guide](../infra/aws/README.md).

## What the evidence does not establish

Tests establish the behaviors above within this simulator. They do not establish orbital accuracy, physical flight timing, real-time scheduling, throughput benchmarks, unconditional exactly-once execution, recovery after loss of the simulator ledger, or flight qualification.

[engine]: ../src/test/java/dev/datnguyen/apogee/GroundEngineTest.java
[planning]: ../src/test/java/dev/datnguyen/apogee/PlanningTest.java
[spacecraft]: ../src/test/java/dev/datnguyen/apogee/SpacecraftTest.java
[wire]: ../src/test/java/dev/datnguyen/apogee/WireTest.java
[database]: ../src/test/java/dev/datnguyen/apogee/PostgresIntegrationTest.java
[console]: ../web/tests/console.spec.ts
[browser-planning]: ../web/tests/planning.spec.ts
