# Operate Apogee

[Project overview](../README.md) · [Code walkthrough](LEARNING.md) · [API reference](PROTOCOL.md)

Use this guide with the [local application](http://127.0.0.1:8081). Start the services using the [quick start](../README.md#run-locally).

## Before an experiment

1. Finish or resolve any active procedure. Cancel pending runs that might start during the experiment.
2. On **Contact planning**, restore continuous contact if windows are enabled.
3. Wait for **Ground service live**, a connected spacecraft link, and fresh telemetry on **Mission console**.
4. Apply **Nominal conditions** to clear injected faults and restore baseline battery/storage.

The browser-to-ground connection and ground-to-spacecraft connection are separate. **Ground service live** can remain visible during a spacecraft blackout.

Nominal conditions does not cancel work, erase history, or change the instrument's current state. Accepted work may still finish after a pause or abort.

## Run an observation

Select **Observation procedure · v1**, then click **Execute observation**.

| Step                 | Default simulated duration | Completion effect                                                              |
| -------------------- | -------------------------- | ------------------------------------------------------------------------------ |
| Power on instrument  | 2 ticks                    | Instrument becomes `READY`.                                                    |
| Collect observation  | 6 ticks                    | Observation count increases by one; storage increases by 20 percentage points. |
| Power off instrument | 2 ticks                    | Instrument becomes `OFF`.                                                      |

A tick occurs about once a second. Scheduling, communication, and display updates add overhead; these durations are not network-latency settings. Collection produces ledger evidence rather than image pixels.

The backend checks contact, connection, fresh telemetry, and the applicable step requirements before sending each command. The default minimum battery is 30% for power-on and collection; collection also requires a ready instrument and storage at or below 80%. Published steps can impose stricter requirements.

## Understand command status

A **procedure** is a saved definition. A **run** is one execution of a specific version. A **command** is one step sent to the spacecraft.

| Command status        | Meaning                                                                         |
| --------------------- | ------------------------------------------------------------------------------- |
| `QUEUED`              | Intent is recorded and awaits dispatch.                                         |
| `SENT`                | Dispatch has been attempted or is about to be attempted; receipt is not proven. |
| `ACCEPTED`            | The spacecraft accepted the command; completion is pending.                     |
| `COMPLETED`           | The spacecraft reported completion.                                             |
| `REJECTED` / `FAILED` | The spacecraft reported a terminal unsuccessful outcome.                        |
| `UNKNOWN`             | Completion could not be established before timeout or after recovery.           |

`UNKNOWN` preserves uncertainty: a missing reply cannot establish whether an action executed. A run pauses on an unknown outcome; it also can pause before dispatch when a requirement is unmet.

## Recover a lost completion

1. Apply **Lost completion acknowledgment**, then execute the default observation procedure.
2. Watch collection finish onboard: the observation count increases, but its completion reply is suppressed.
3. After the verification timeout, the command becomes **UNKNOWN** and the run becomes **PAUSED**. The default timeout is 12 seconds.
4. Click **Reconcile spacecraft state**. This sends a status query using the existing command ID.
5. Wait for the collection command to show **COMPLETED**, then click **Resume** to run the remaining power-off step.
6. Confirm that reconciliation did not add another observation. Apply **Nominal conditions** before the next experiment.

Reconciliation never resends the original command. A query can return `ACCEPTED` if work is still in progress. If it returns `NOT_FOUND`, uncertainty remains and the engine does not authorize a retry.

## Publish a procedure

On **Procedures**, choose **New procedure** or load an existing definition. Edit or reorder its steps, then publish a version.

- Definitions contain 2–8 steps and must end with the instrument powered off.
- Power transitions take two ticks. Collection duration can be 2–20 ticks.
- Each step has a verification timeout and applicable battery/storage requirements.
- Publishing preserves previous versions. Scheduled and executing runs retain the definition saved when they were created.
- A stale edit is rejected if another publication changed the version you started from. Reload the saved definition before publishing again.

**Open saved version** selects a definition on the console. Use **Execute** to start it.

## Plan a contact-loss demonstration

Start with no active or pending runs and Nominal conditions applied.

1. Create a new procedure, for example `CONTACT-DEMO`, with the default power-on/collection/power-off sequence. Set collection duration to **10 seconds** and its verification timeout to **12 seconds**. Publish version 1.
2. On **Contact planning**, set the cycle length to **30 seconds** and contact length to **8 seconds**. Set first contact to **30 seconds** to give yourself time to schedule the run, then apply the plan.
3. Select `CONTACT-DEMO` v1 under **Schedule a procedure**. Set start after to **0 seconds**, leave the start deadline at **180 seconds**, and schedule it before the first window opens.
4. The run starts when contact and fresh telemetry become available. The TCP connection closes after the eight-second window while the spacecraft continues collection.
5. Wait for the command to become `UNKNOWN`. At the next contact, reconcile its result and explicitly resume to power off. If the window is too short to finish recovery, restore continuous contact first.
6. Restore continuous contact when finished.

The start deadline limits when a scheduled run may **begin**, not when it must finish. A missed deadline fails a pending run without sending commands. **Use next contact** fills in a start delay; scheduling still requires pressing **Schedule procedure**.

The globe shows the saved cycle as a synthetic surface track. Dragging rotates the view. Station coordinates affect the diagram; contact timing comes from the configured cycle, not orbital propagation or radio visibility.

## Other controls

| Control                        | Behavior                                                                                                                                                              |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Pause**                      | Stops future steps; accepted work may continue onboard.                                                                                                               |
| **Resume**                     | Continues a paused run when contact, link, and telemetry checks pass. An `UNKNOWN` command must be reconciled first.                                                  |
| **Abort**                      | Stops future steps without undoing executed effects or automatically powering off. Unresolved work keeps the instrument reservation until its outcome is established. |
| **Cancel scheduled run**       | Cancels pending work without sending a command.                                                                                                                       |
| **Restore continuous contact** | Permits reconnection outside configured windows. Paused work still requires explicit recovery/resume.                                                                 |
| **Run history / replay**       | Displays recorded decisions. Scrubbing the slider never executes commands.                                                                                            |
| **Export JSON**                | Downloads the run's definition, commands, and up to 250 recorded events.                                                                                              |

## Failure scenarios

| Scenario                           | What changes                                                                                                                |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| **Lost completion acknowledgment** | Suppresses collection's completion reply after the result is saved onboard.                                                 |
| **Frozen telemetry**               | Stops measurement delivery; spacecraft execution continues. Measurements older than five seconds cannot authorize commands. |
| **Low battery**                    | Sets simulated battery to 15%, blocking applicable commands.                                                                |
| **Instrument rejection**           | Makes the spacecraft reject collection commands.                                                                            |
| **Disconnect 15s**                 | Closes the ground TCP connection and defers reconnection for 15 seconds.                                                    |
| **Nominal conditions**             | Clears the injected fault and restores battery to 82% and storage to 12%; retains command history.                          |

For process-crash recovery and automated evidence, see [verification](VERIFICATION.md). For a connection or startup problem, see [local troubleshooting](DEVELOPMENT.md#troubleshooting).
