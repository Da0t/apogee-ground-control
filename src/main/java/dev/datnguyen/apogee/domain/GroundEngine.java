package dev.datnguyen.apogee.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.datnguyen.apogee.domain.Models.*;
import java.time.Clock;
import java.util.*;

/** Single-writer procedure engine. Database transactions never include network sends. */
public class GroundEngine {
  public interface Link {
    boolean connected();

    void send(Map<String, ?> message);

    default void contactAllowed(boolean allowed) {}
  }

  private final MissionStore store;
  private final Clock clock;
  private final Link link;
  private Telemetry telemetry;
  private long lastQuery;

  public GroundEngine(MissionStore store, Clock clock, Link link) {
    this.store = store;
    this.clock = clock;
    this.link = link;
  }

  private long now() {
    return clock.millis();
  }

  private Run active() {
    return store.activeRun();
  }

  private Contacts.Status contacts() {
    return Contacts.status(store.contactPlan(), now());
  }

  private Procedure definition(String id, Integer version) {
    Procedure p = store.procedure(id, version);
    if (p == null && "OBSERVATION-001".equals(id) && (version == null || version == 1))
      p = Models.defaultProcedure();
    if (p == null) throw new IllegalArgumentException("Unknown procedure version");
    return p;
  }

  public synchronized Procedure publish(
      String id, int baseVersion, String name, String description, List<Step> steps) {
    Procedure latest = store.procedure(id, null);
    if (latest == null && "OBSERVATION-001".equals(id)) latest = Models.defaultProcedure();
    if (baseVersion != (latest == null ? 0 : latest.version()))
      throw new IllegalStateException(
          "Procedure changed; reload its latest version before publishing");
    Procedure next = new Procedure(id, baseVersion + 1, name, description, now(), steps);
    Models.validateProcedure(next);
    store.atomic(
        () -> {
          if ("OBSERVATION-001".equals(id) && store.procedure(id, 1) == null)
            store.save(Models.defaultProcedure());
          store.save(next);
          event("INFO", "Published " + id + " v" + next.version(), null, null);
        });
    return next;
  }

  public synchronized Contacts.Status configureContacts(Contacts.Plan plan) {
    Contacts.validate(plan, now());
    if (active() != null && plan.enabled())
      throw new IllegalStateException("Resolve the active run before changing its contact plan");
    store.atomic(
        () -> {
          store.save(plan);
          event(
              "INFO",
              plan.enabled()
                  ? "Simulated contact windows enabled for " + plan.station()
                  : "Continuous contact restored",
              null,
              null);
        });
    telemetry = null; // An old measurement cannot authorize commands after a link policy change.
    link.contactAllowed(contacts().open());
    return contacts();
  }

  private Run run(UUID id) {
    Run r = store.run(id);
    if (r == null) throw new IllegalArgumentException("Unknown run");
    return r;
  }

  private void event(String level, String message, Run run, Command c) {
    store.event(now(), level, message, run == null ? null : run.id, c == null ? null : c.id);
  }

  public synchronized void recover() {
    store.atomic(
        () -> {
          Run r = active();
          if (r != null) {
            for (Command c : store.commands(r.id))
              if (!c.resolved() && c.status != CommandStatus.QUEUED) {
                c.status = CommandStatus.UNKNOWN;
                c.reason = "Ground service restarted; query spacecraft before continuing";
                c.updatedAt = now();
                store.save(c);
              }
            if (r.status != RunStatus.ABORTING) r.status = RunStatus.PAUSED;
            r.reason = "Recovered durable state. Reconcile any unresolved command, then resume.";
            store.save(r);
            event("WARN", r.reason, r, null);
          }
        });
  }

  public synchronized Run start(UUID id) {
    return start(id, "OBSERVATION-001", null, null, null);
  }

  public synchronized Run start(
      UUID id, String procedureId, Integer version, Long notBefore, Long expiresAt) {
    if (id == null) throw new IllegalArgumentException("requestId is required");
    Run existing = store.run(id);
    if (existing != null) return existing;
    Procedure definition =
        definition(procedureId == null ? "OBSERVATION-001" : procedureId, version);
    Run r = new Run(id, now());
    r.definition = definition;
    r.procedure = definition.id();
    r.version = definition.version();
    if (notBefore != null) {
      if (expiresAt == null
          || notBefore < now() - 5000
          || notBefore > now() + 604_800_000L
          || expiresAt <= Math.max(notBefore, now())
          || expiresAt - notBefore > 86_400_000L)
        throw new IllegalArgumentException(
            "Choose a start within seven days and a start deadline within 24 hours");
      if (store.scheduledRuns().size() >= 50)
        throw new IllegalStateException("The schedule holds at most 50 pending runs");
      r.status = RunStatus.SCHEDULED;
      r.notBefore = notBefore;
      r.expiresAt = expiresAt;
      r.reason = "Waiting for scheduled time, contact and telemetry guards";
    } else {
      if (expiresAt != null)
        throw new IllegalArgumentException("A start deadline requires a scheduled start");
      if (active() != null)
        throw new IllegalStateException("The instrument is reserved by an active procedure");
      if (!contacts().open()) throw new IllegalStateException("Outside a simulated contact window");
      if (!link.connected()) throw new IllegalStateException("Spacecraft link is disconnected");
      String failure = Models.precondition(definition.steps().getFirst(), telemetry, now());
      if (failure != null) throw new IllegalStateException(failure);
    }
    store.atomic(
        () -> {
          store.save(r);
          event(
              "INFO",
              definition.name()
                  + " v"
                  + definition.version()
                  + (r.status == RunStatus.SCHEDULED ? " scheduled" : " started"),
              r,
              null);
        });
    return r;
  }

  private void advanceSchedule() {
    for (Run waiting : store.scheduledRuns()) {
      if (now() >= waiting.expiresAt) {
        waiting.status = RunStatus.FAILED;
        waiting.reason = "Start deadline expired; no commands were transmitted";
        store.atomic(
            () -> {
              store.save(waiting);
              event("WARN", waiting.reason, waiting, null);
            });
      }
    }
    if (active() != null
        || !contacts().open()
        || !link.connected()
        || !Models.fresh(telemetry, now())) return;
    for (Run waiting : store.scheduledRuns()) {
      if (now() < waiting.notBefore) continue;
      String failure = Models.precondition(waiting.definition.steps().getFirst(), telemetry, now());
      if (failure != null) {
        if (!failure.equals(waiting.reason)) {
          waiting.reason = failure;
          store.save(waiting);
        }
        continue;
      }
      waiting.status = RunStatus.RUNNING;
      waiting.reason = "Scheduled run activated with fresh telemetry and contact";
      store.atomic(
          () -> {
            store.save(waiting);
            event("INFO", waiting.reason, waiting, null);
          });
      break;
    }
  }

  public synchronized void action(UUID id, String action) {
    Run r = run(id);
    if (r.status == RunStatus.COMPLETED
        || r.status == RunStatus.FAILED
        || r.status == RunStatus.ABORTED)
      throw new IllegalStateException("Run is already terminal");
    store.atomic(
        () -> {
          Command c = r.commandId == null ? null : store.command(r.commandId);
          switch (action) {
            case "pause" -> {
              if (r.status != RunStatus.RUNNING)
                throw new IllegalStateException("Only a running procedure can be paused");
              r.status = RunStatus.PAUSED;
              r.reason = "Operator paused future steps; an in-flight command can still finish";
            }
            case "resume" -> {
              if (r.status != RunStatus.PAUSED)
                throw new IllegalStateException("Only a paused procedure can be resumed");
              if (c != null && c.status == CommandStatus.UNKNOWN)
                throw new IllegalStateException("Reconcile the unknown command before resuming");
              if (!contacts().open() || !link.connected() || !Models.fresh(telemetry, now()))
                throw new IllegalStateException("A live link and fresh telemetry are required");
              r.status = RunStatus.RUNNING;
              r.reason = "Operator resumed execution";
            }
            case "abort" -> {
              if (c != null && c.status == CommandStatus.QUEUED) {
                c.status = CommandStatus.REJECTED;
                c.reason = "Aborted before transmission";
                store.save(c);
              }
              r.status = c != null && !c.resolved() ? RunStatus.ABORTING : RunStatus.ABORTED;
              r.reason =
                  r.status == RunStatus.ABORTING
                      ? "No further steps. Waiting for the in-flight outcome; instrument state is"
                          + " unchanged."
                      : "Aborted. Previously executed actions are not undone.";
            }
            default -> throw new IllegalArgumentException("Unknown action");
          }
          store.save(r);
          event("WARN", r.reason, r, c);
        });
  }

  public synchronized void reconcile(UUID id) {
    Run r = run(id);
    Command c = r.commandId == null ? null : store.command(r.commandId);
    if (c == null || c.resolved())
      throw new IllegalStateException("No unresolved command to reconcile");
    if (!contacts().open() || !link.connected())
      throw new IllegalStateException("Wait for a live contact window before querying");
    if (now() - lastQuery < 1000)
      throw new IllegalStateException("Wait one second between status queries");
    lastQuery = now();
    event("INFO", "Querying the spacecraft ledger; no command is being re-executed", r, c);
    link.send(Map.of("version", 1, "type", "QUERY", "commandId", c.id.toString()));
  }

  public synchronized void tick() {
    link.contactAllowed(contacts().open());
    advanceSchedule();
    Run r = active();
    if (r == null) return;
    Command c = r.commandId == null ? null : store.command(r.commandId);
    if (c != null
        && (c.status == CommandStatus.SENT || c.status == CommandStatus.ACCEPTED)
        && now() - c.sentAt > c.timeoutSeconds * 1000L) {
      store.atomic(
          () -> {
            c.status = CommandStatus.UNKNOWN;
            c.reason =
                "Completion was not verified within "
                    + c.timeoutSeconds
                    + " seconds; execution may have occurred";
            c.updatedAt = now();
            store.save(c);
            if (r.status != RunStatus.ABORTING) r.status = RunStatus.PAUSED;
            r.reason = c.reason;
            store.save(r);
            event("WARN", c.reason, r, c);
          });
      return;
    }
    if (r.status == RunStatus.ABORTING) {
      if (c == null || c.resolved())
        store.atomic(
            () -> {
              r.status = RunStatus.ABORTED;
              r.reason = "In-flight outcome recorded; no further steps executed";
              store.save(r);
              event("WARN", r.reason, r, c);
            });
      return;
    }
    if (r.status != RunStatus.RUNNING) return;
    if (c != null && c.resolved()) {
      store.atomic(
          () -> {
            if (c.status != CommandStatus.COMPLETED) {
              r.status = RunStatus.FAILED;
              r.reason = c.reason;
            } else {
              r.step++;
              r.commandId = null;
              r.reason = "Step verified; evaluating next step";
              if (r.step == r.definition.steps().size()) {
                r.status = RunStatus.COMPLETED;
                r.reason = "Procedure verified and instrument powered off";
                event("SUCCESS", r.reason, r, c);
              }
            }
            store.save(r);
          });
      return;
    }
    if (c != null && c.status != CommandStatus.QUEUED) return;
    Step step = r.definition.steps().get(r.step);
    CommandKind kind = step.kind();
    String failure =
        !contacts().open()
            ? "Contact window closed; resume after communication returns"
            : !link.connected()
                ? "Spacecraft link is disconnected"
                : Models.precondition(step, telemetry, now());
    if (failure != null) {
      store.atomic(
          () -> {
            r.status = RunStatus.PAUSED;
            r.reason = failure;
            store.save(r);
            event("WARN", failure, r, null);
          });
      return;
    }
    Command outgoing = c == null ? new Command(UUID.randomUUID(), r.id, kind, now()) : c;
    outgoing.stepIndex = r.step;
    outgoing.durationSeconds = step.durationSeconds();
    outgoing.timeoutSeconds = step.timeoutSeconds();
    store.atomic(
        () -> {
          store.save(outgoing);
          r.commandId = outgoing.id;
          r.reason = "Executing " + kind;
          store.save(r);
        });
    // Commit before touching the socket. A crash in this interval is recovered conservatively as
    // UNKNOWN.
    store.atomic(
        () -> {
          outgoing.status = CommandStatus.SENT;
          outgoing.sentAt = now();
          outgoing.updatedAt = now();
          outgoing.reason = "Sent; awaiting spacecraft acceptance";
          store.save(outgoing);
          event("INFO", kind + " dispatched", r, outgoing);
        });
    if (!contacts().open()) {
      link.contactAllowed(false);
      return;
    }
    link.send(
        Map.of(
            "durationSeconds",
            outgoing.durationSeconds,
            "version",
            1,
            "type",
            "COMMAND",
            "commandId",
            outgoing.id.toString(),
            "kind",
            kind.name()));
  }

  public synchronized void receive(JsonNode n) {
    if (!contacts().open()) return;
    if ("TELEMETRY".equals(n.path("type").asText())) {
      String boot = n.path("bootId").asText();
      long sequence = n.path("sequence").asLong(-1);
      double battery = n.path("battery").asDouble(-1), storage = n.path("storage").asDouble(-1);
      String instrument = n.path("instrument").asText();
      if (boot.isBlank()
          || sequence < 0
          || !Double.isFinite(battery)
          || !Double.isFinite(storage)
          || battery < 0
          || battery > 100
          || storage < 0
          || storage > 100
          || !Set.of("OFF", "READY", "COLLECTING").contains(instrument)) return;
      if (telemetry != null && boot.equals(telemetry.bootId()) && sequence <= telemetry.sequence())
        return;
      telemetry =
          new Telemetry(
              boot,
              sequence,
              n.path("tick").asLong(),
              battery,
              storage,
              instrument,
              n.path("mode").asText(),
              n.path("observations").asInt(),
              n.path("fault").asText(),
              now());
      store.telemetry(telemetry);
      return;
    }
    if (!"ACK".equals(n.path("type").asText())) return;
    UUID id;
    try {
      id = UUID.fromString(n.path("commandId").asText());
    } catch (IllegalArgumentException e) {
      return;
    }
    Command c = store.command(id);
    if (c == null || c.resolved()) return;
    String status = n.path("status").asText();
    if ("NOT_FOUND".equals(status)) {
      event(
          "WARN",
          "Spacecraft has no ledger entry. Outcome remains unknown; automatic resend is disabled.",
          run(c.runId),
          c);
      return;
    }
    if (!Set.of("ACCEPTED", "COMPLETED", "REJECTED", "FAILED").contains(status)) return;
    CommandStatus next = CommandStatus.valueOf(status);
    if (c.status == next) return;
    store.atomic(
        () -> {
          c.status = next;
          c.updatedAt = now();
          c.reason = n.path("reason").asText("Spacecraft reported " + status);
          if (next == CommandStatus.ACCEPTED) c.sentAt = now();
          store.save(c);
          event(
              next == CommandStatus.COMPLETED
                  ? "SUCCESS"
                  : next == CommandStatus.ACCEPTED ? "INFO" : "WARN",
              c.kind + ": " + c.reason,
              run(c.runId),
              c);
        });
  }

  public synchronized void fault(String mode) {
    if (mode == null
        || !Set.of("NONE", "DROP_COMPLETION", "STALE_TELEMETRY", "LOW_BATTERY", "REJECT_CAPTURE")
            .contains(mode)) throw new IllegalArgumentException("Unsupported scenario");
    if (!link.connected()) throw new IllegalStateException("Spacecraft link is disconnected");
    link.send(Map.of("version", 1, "type", "FAULT", "mode", mode));
    event("WARN", "Scenario requested: " + mode, active(), null);
  }

  public synchronized Map<String, Object> snapshot() {
    Run r = active();
    if (r == null)
      r =
          store.runs().stream()
              .filter(run -> run.status != RunStatus.SCHEDULED)
              .findFirst()
              .orElse(null);
    var m = new LinkedHashMap<String, Object>();
    m.put("connected", contacts().open() && link.connected());
    m.put("fresh", contacts().open() && Models.fresh(telemetry, now()));
    m.put("now", now());
    m.put("telemetry", telemetry);
    m.put("run", r);
    m.put("commands", r == null ? List.of() : store.commands(r.id));
    m.put("events", store.events(null));
    m.put("samples", store.samples());
    m.put("runs", store.runs());
    var procedures = new ArrayList<>(store.procedures());
    if (procedures.stream().noneMatch(p -> p.id().equals("OBSERVATION-001") && p.version() == 1))
      procedures.add(Models.defaultProcedure());
    m.put("procedures", procedures);
    m.put("contacts", contacts());
    m.put("scheduled", store.scheduledRuns());
    return m;
  }
}
