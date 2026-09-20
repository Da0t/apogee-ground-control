package dev.datnguyen.apogee.domain;

import java.util.*;

public final class Models {
  private Models() {}

  public enum CommandKind {
    POWER_ON,
    CAPTURE,
    POWER_OFF
  }

  public enum CommandStatus {
    QUEUED,
    SENT,
    ACCEPTED,
    COMPLETED,
    REJECTED,
    FAILED,
    UNKNOWN
  }

  public enum RunStatus {
    SCHEDULED,
    RUNNING,
    PAUSED,
    ABORTING,
    COMPLETED,
    FAILED,
    ABORTED
  }

  public static final List<CommandKind> PROCEDURE =
      List.of(CommandKind.POWER_ON, CommandKind.CAPTURE, CommandKind.POWER_OFF);

  public record Step(
      CommandKind kind,
      int durationSeconds,
      int timeoutSeconds,
      double minBattery,
      double maxStorage) {}

  public record Procedure(
      String id, int version, String name, String description, long createdAt, List<Step> steps) {
    public Procedure {
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  public static Procedure defaultProcedure() {
    return new Procedure(
        "OBSERVATION-001",
        1,
        "Observation procedure",
        "Collect an observation, verify storage, and return the instrument to standby.",
        0,
        List.of(
            new Step(CommandKind.POWER_ON, 2, 12, 30, 80),
            new Step(CommandKind.CAPTURE, 6, 12, 30, 80),
            new Step(CommandKind.POWER_OFF, 2, 12, 30, 80)));
  }

  public static void validateProcedure(Procedure p) {
    if (p.id() == null
        || !p.id().matches("[A-Z][A-Z0-9_-]{2,39}")
        || p.name() == null
        || p.name().isBlank()
        || p.name().length() > 80
        || p.description() == null
        || p.description().length() > 400
        || p.steps().size() < 2
        || p.steps().size() > 8)
      throw new IllegalArgumentException(
          "Use a 3–40 character uppercase ID, a name, and 2–8 typed steps");
    boolean powered = false;
    for (Step s : p.steps()) {
      if (s == null
          || s.kind() == null
          || s.durationSeconds() < 2
          || s.durationSeconds() > 20
          || (s.kind() != CommandKind.CAPTURE && s.durationSeconds() != 2)
          || s.timeoutSeconds() < s.durationSeconds() + 2
          || s.timeoutSeconds() > 120
          || !Double.isFinite(s.minBattery())
          || s.minBattery() < 30
          || s.minBattery() > 100
          || !Double.isFinite(s.maxStorage())
          || s.maxStorage() < 0
          || s.maxStorage() > 80)
        throw new IllegalArgumentException(
            "Invalid step duration, verification timeout, or telemetry guard");
      if (s.kind() == CommandKind.POWER_ON) {
        if (powered)
          throw new IllegalArgumentException("Instrument is already powered on in this procedure");
        powered = true;
      } else {
        if (!powered) throw new IllegalArgumentException("Power on before collection or power off");
        if (s.kind() == CommandKind.POWER_OFF) powered = false;
      }
    }
    if (powered)
      throw new IllegalArgumentException("Procedure must finish with the instrument powered off");
  }

  public record Telemetry(
      String bootId,
      long sequence,
      long tick,
      double battery,
      double storage,
      String instrument,
      String mode,
      int observations,
      String fault,
      long receivedAt) {}

  public record Event(
      long sequence, long at, String level, String message, UUID runId, UUID commandId) {}

  public static class Run {
    public UUID id;
    public String procedure = "OBSERVATION-001";
    public int version = 1;
    public RunStatus status = RunStatus.RUNNING;
    public int step;
    public UUID commandId;
    public long createdAt;
    public String reason = "Ready to execute";
    public Procedure definition = defaultProcedure();
    public long notBefore, expiresAt;

    public Run() {}

    public Run(UUID id, long now) {
      this.id = id;
      createdAt = now;
    }
  }

  public static class Command {
    public UUID id;
    public UUID runId;
    public CommandKind kind;
    public CommandStatus status = CommandStatus.QUEUED;
    public long createdAt;
    public long sentAt;
    public long updatedAt;
    public String reason = "Intent persisted before transmission";
    public int stepIndex = -1;
    public int durationSeconds;
    public int timeoutSeconds = 12;

    public Command() {}

    public Command(UUID id, UUID runId, CommandKind kind, long now) {
      this.id = id;
      this.runId = runId;
      this.kind = kind;
      createdAt = now;
      updatedAt = now;
    }

    public boolean resolved() {
      return status == CommandStatus.COMPLETED
          || status == CommandStatus.FAILED
          || status == CommandStatus.REJECTED;
    }
  }

  public static boolean fresh(Telemetry t, long now) {
    return t != null && now >= t.receivedAt && now - t.receivedAt <= 5000;
  }

  public static String precondition(CommandKind kind, Telemetry t, long now) {
    if (!fresh(t, now)) return "Telemetry is stale or unavailable (maximum age: 5 seconds)";
    if (kind != CommandKind.POWER_OFF && t.battery < 30) return "Battery must be at least 30%";
    if (kind == CommandKind.CAPTURE && !"READY".equals(t.instrument))
      return "Instrument must be READY";
    if (kind == CommandKind.CAPTURE && t.storage > 80)
      return "At least 20% storage must be available";
    return null;
  }

  public static String precondition(Step s, Telemetry t, long now) {
    String failure = precondition(s.kind(), t, now);
    if (failure != null) return failure;
    if (s.kind() != CommandKind.POWER_OFF && t.battery() < s.minBattery())
      return "Procedure requires battery at least " + s.minBattery() + "%";
    if (s.kind() == CommandKind.CAPTURE && t.storage() > s.maxStorage())
      return "Procedure requires storage at most " + s.maxStorage() + "%";
    return null;
  }
}
