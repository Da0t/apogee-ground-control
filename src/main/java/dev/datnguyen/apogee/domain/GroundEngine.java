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
    return store.runs().stream()
        .filter(
            r ->
                r.status == RunStatus.RUNNING
                    || r.status == RunStatus.PAUSED
                    || r.status == RunStatus.ABORTING)
        .findFirst()
        .orElse(null);
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
    Run existing = store.run(id);
    if (existing != null) return existing;
    if (active() != null)
      throw new IllegalStateException("The instrument is reserved by an active procedure");
    if (!link.connected()) throw new IllegalStateException("Spacecraft link is disconnected");
    String failure = Models.precondition(CommandKind.POWER_ON, telemetry, now());
    if (failure != null) throw new IllegalStateException(failure);
    Run r = new Run(id, now());
    store.atomic(
        () -> {
          store.save(r);
          event("INFO", "Observation procedure v1 started", r, null);
        });
    return r;
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
              if (!link.connected() || !Models.fresh(telemetry, now()))
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
    if (!link.connected()) throw new IllegalStateException("Spacecraft link is disconnected");
    if (now() - lastQuery < 1000)
      throw new IllegalStateException("Wait one second between status queries");
    lastQuery = now();
    event("INFO", "Querying the spacecraft ledger; no command is being re-executed", r, c);
    link.send(Map.of("version", 1, "type", "QUERY", "commandId", c.id.toString()));
  }

  public synchronized void tick() {
    Run r = active();
    if (r == null) return;
    Command c = r.commandId == null ? null : store.command(r.commandId);
    if (c != null
        && (c.status == CommandStatus.SENT || c.status == CommandStatus.ACCEPTED)
        && now() - c.sentAt > 12000) {
      store.atomic(
          () -> {
            c.status = CommandStatus.UNKNOWN;
            c.reason = "Completion was not verified within 12 seconds; execution may have occurred";
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
              if (r.step == Models.PROCEDURE.size()) {
                r.status = RunStatus.COMPLETED;
                r.reason = "Observation stored and instrument powered off";
                event("SUCCESS", r.reason, r, c);
              }
            }
            store.save(r);
          });
      return;
    }
    if (c != null && c.status != CommandStatus.QUEUED) return;
    CommandKind kind = Models.PROCEDURE.get(r.step);
    String failure =
        !link.connected()
            ? "Spacecraft link is disconnected"
            : Models.precondition(kind, telemetry, now());
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
    link.send(
        Map.of(
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
    if (!Set.of("NONE", "DROP_COMPLETION", "STALE_TELEMETRY", "LOW_BATTERY", "REJECT_CAPTURE")
        .contains(mode)) throw new IllegalArgumentException("Unsupported scenario");
    if (!link.connected()) throw new IllegalStateException("Spacecraft link is disconnected");
    link.send(Map.of("version", 1, "type", "FAULT", "mode", mode));
    event("WARN", "Scenario requested: " + mode, active(), null);
  }

  public synchronized Map<String, Object> snapshot() {
    Run r = active();
    if (r == null) r = store.runs().stream().findFirst().orElse(null);
    var m = new LinkedHashMap<String, Object>();
    m.put("connected", link.connected());
    m.put("fresh", Models.fresh(telemetry, now()));
    m.put("now", now());
    m.put("telemetry", telemetry);
    m.put("run", r);
    m.put("commands", r == null ? List.of() : store.commands(r.id));
    m.put("events", store.events(null));
    m.put("samples", store.samples());
    m.put("runs", store.runs());
    return m;
  }
}
