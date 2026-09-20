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
    RUNNING,
    PAUSED,
    ABORTING,
    COMPLETED,
    FAILED,
    ABORTED
  }

  public static final List<CommandKind> PROCEDURE =
      List.of(CommandKind.POWER_ON, CommandKind.CAPTURE, CommandKind.POWER_OFF);

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
}
