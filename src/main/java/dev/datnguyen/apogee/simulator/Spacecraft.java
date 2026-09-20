package dev.datnguyen.apogee.simulator;

import dev.datnguyen.apogee.domain.Models.CommandKind;
import dev.datnguyen.apogee.protocol.Wire;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Illustrative spacecraft model. All resource effects and ledger outcomes share one atomic
 * snapshot.
 */
public class Spacecraft {
  public static class Entry {
    public String kind, status, reason;
    public long dueTick;
    public int durationSeconds;

    public Entry() {}

    Entry(String kind, String status, String reason, long dueTick) {
      this.kind = kind;
      this.status = status;
      this.reason = reason;
      this.dueTick = dueTick;
    }
  }

  public static class State {
    public double battery = 82, storage = 12;
    public long tick, sequence;
    public int observations;
    public String instrument = "OFF", fault = "NONE";
    public Map<String, Entry> ledger = new LinkedHashMap<>();
  }

  private final Path file;
  private final String bootId = UUID.randomUUID().toString();
  private State state;

  public Spacecraft(Path file) throws IOException {
    this.file = file;
    state =
        Files.exists(file)
            ? Wire.JSON.readValue(Files.readAllBytes(file), State.class)
            : new State();
  }

  public synchronized State state() {
    try {
      return Wire.JSON.readValue(Wire.JSON.writeValueAsBytes(state), State.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void save() throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    try (var out = new FileOutputStream(temp.toFile())) {
      out.write(Wire.JSON.writeValueAsBytes(state));
      out.getFD().sync();
    }
    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  public synchronized Map<String, Object> command(String id, String kind) throws IOException {
    return command(id, kind, 0);
  }

  public synchronized Map<String, Object> command(String id, String kind, int duration)
      throws IOException {
    UUID.fromString(id);
    CommandKind k = CommandKind.valueOf(kind);
    int seconds = duration == 0 ? (k == CommandKind.CAPTURE ? 6 : 2) : duration;
    if (seconds < 2 || seconds > 20 || (k != CommandKind.CAPTURE && seconds != 2))
      throw new IllegalArgumentException("Invalid command duration");
    Entry old = state.ledger.get(id);
    if (old != null) {
      int oldSeconds =
          old.durationSeconds == 0 ? (old.kind.equals("CAPTURE") ? 6 : 2) : old.durationSeconds;
      if (!old.kind.equals(kind) || oldSeconds != seconds)
        throw new IllegalArgumentException("Command ID reused with different arguments");
      return ack(id, old);
    }
    if (state.ledger.size() >= 10000)
      throw new IllegalStateException(
          "Ledger capacity reached; archive/reset simulator data offline");
    String rejected = null;
    if (state.ledger.values().stream().anyMatch(e -> e.status.equals("ACCEPTED")))
      rejected = "Instrument is reserved by another command";
    else if (k != CommandKind.POWER_OFF && state.battery < 30)
      rejected = "Spacecraft rejected: battery below 30%";
    else if (k == CommandKind.CAPTURE && !state.instrument.equals("READY"))
      rejected = "Instrument is not ready";
    else if (k == CommandKind.CAPTURE && state.storage > 80) rejected = "Insufficient storage";
    else if (k == CommandKind.CAPTURE && state.fault.equals("REJECT_CAPTURE"))
      rejected = "Injected instrument rejection";
    Entry entry =
        new Entry(
            kind,
            rejected == null ? "ACCEPTED" : "REJECTED",
            rejected == null ? "Accepted by spacecraft" : rejected,
            state.tick + seconds);
    entry.durationSeconds = seconds;
    state.ledger.put(id, entry);
    if (rejected == null && k == CommandKind.CAPTURE) state.instrument = "COLLECTING";
    save();
    return ack(id, entry);
  }

  public synchronized Map<String, Object> query(String id) {
    Entry e = state.ledger.get(id);
    return e == null
        ? Map.of(
            "version",
            1,
            "type",
            "ACK",
            "commandId",
            id,
            "status",
            "NOT_FOUND",
            "reason",
            "No ledger entry")
        : ack(id, e);
  }

  private Map<String, Object> ack(String id, Entry e) {
    return Map.of(
        "version", 1, "type", "ACK", "commandId", id, "status", e.status, "reason", e.reason);
  }

  public synchronized List<Map<String, Object>> tick() throws IOException {
    state.tick++;
    state.sequence++;
    state.battery =
        Math.max(0, Math.min(100, state.battery + (state.instrument.equals("OFF") ? 0.04 : -0.08)));
    var replies = new ArrayList<Map<String, Object>>();
    for (var pair : state.ledger.entrySet()) {
      Entry e = pair.getValue();
      if (!e.status.equals("ACCEPTED") || e.dueTick > state.tick) continue;
      switch (CommandKind.valueOf(e.kind)) {
        case POWER_ON -> state.instrument = "READY";
        case CAPTURE -> {
          state.observations++;
          state.storage = Math.min(100, state.storage + 20);
          state.instrument = "READY";
        }
        case POWER_OFF -> state.instrument = "OFF";
      }
      e.status = "COMPLETED";
      e.reason =
          e.kind.equals("CAPTURE")
              ? "Observation " + state.observations + " stored"
              : "Verified " + e.kind;
      if (!(state.fault.equals("DROP_COMPLETION") && e.kind.equals("CAPTURE")))
        replies.add(ack(pair.getKey(), e));
    }
    save();
    return replies;
  }

  public synchronized void fault(String mode) throws IOException {
    if (!Set.of("NONE", "DROP_COMPLETION", "STALE_TELEMETRY", "LOW_BATTERY", "REJECT_CAPTURE")
        .contains(mode)) throw new IllegalArgumentException("Unknown scenario");
    if (mode.equals("LOW_BATTERY")) state.battery = 15;
    if (mode.equals("NONE")) {
      state.battery = 82;
      state.storage = 12;
    }
    state.fault = mode;
    save();
  }

  public synchronized Map<String, Object> telemetry() {
    var m = new LinkedHashMap<String, Object>();
    m.put("version", 1);
    m.put("type", "TELEMETRY");
    m.put("bootId", bootId);
    m.put("sequence", state.sequence);
    m.put("tick", state.tick);
    m.put("battery", state.battery);
    m.put("storage", state.storage);
    m.put("instrument", state.instrument);
    m.put("mode", state.battery < 30 ? "POWER_SAVE" : "NOMINAL");
    m.put("observations", state.observations);
    m.put("fault", state.fault);
    return m;
  }
}
