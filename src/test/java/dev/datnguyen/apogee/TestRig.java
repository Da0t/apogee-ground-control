package dev.datnguyen.apogee;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.domain.Models.*;
import dev.datnguyen.apogee.protocol.Wire;
import java.time.*;
import java.util.*;

class TestRig {
  static class TestClock extends Clock {
    long value = 100000;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(value);
    }

    public long millis() {
      return value;
    }

    void advance(long ms) {
      value += ms;
    }
  }

  static class TestLink implements GroundEngine.Link {
    boolean live = true;
    List<Map<String, ?>> messages = new ArrayList<>();

    public boolean connected() {
      return live;
    }

    public void send(Map<String, ?> message) {
      messages.add(message);
    }
  }

  static class MemoryStore implements MissionStore {
    Map<UUID, Run> runs = new LinkedHashMap<>();
    Map<UUID, Command> commands = new LinkedHashMap<>();
    List<Event> events = new ArrayList<>();
    List<Telemetry> samples = new ArrayList<>();

    <T> T copy(T value, Class<T> cls) {
      return Wire.JSON.convertValue(value, cls);
    }

    public void atomic(Runnable r) {
      r.run();
    }

    public List<Run> runs() {
      return runs.values().stream().map(r -> copy(r, Run.class)).toList();
    }

    public Run run(UUID id) {
      return runs.containsKey(id) ? copy(runs.get(id), Run.class) : null;
    }

    public List<Command> commands(UUID id) {
      return commands.values().stream()
          .filter(c -> c.runId.equals(id))
          .map(c -> copy(c, Command.class))
          .toList();
    }

    public Command command(UUID id) {
      return commands.containsKey(id) ? copy(commands.get(id), Command.class) : null;
    }

    public void save(Run r) {
      runs.put(r.id, copy(r, Run.class));
    }

    public void save(Command c) {
      commands.put(c.id, copy(c, Command.class));
    }

    public void event(long at, String level, String message, UUID run, UUID command) {
      events.add(new Event(events.size() + 1, at, level, message, run, command));
    }

    public List<Event> events(UUID run) {
      return events.stream().filter(e -> run == null || run.equals(e.runId())).toList();
    }

    public void telemetry(Telemetry t) {
      samples.add(t);
    }

    public List<Telemetry> samples() {
      return samples;
    }
  }

  static void telemetry(GroundEngine engine, long sequence, double battery, String instrument) {
    engine.receive(
        Wire.JSON.valueToTree(
            Map.of(
                "type",
                "TELEMETRY",
                "bootId",
                "test-boot",
                "sequence",
                sequence,
                "battery",
                battery,
                "storage",
                12,
                "instrument",
                instrument,
                "mode",
                "NOMINAL",
                "observations",
                0)));
  }

  static void ack(GroundEngine engine, UUID id, String status) {
    engine.receive(
        Wire.JSON.valueToTree(
            Map.of(
                "type",
                "ACK",
                "commandId",
                id.toString(),
                "status",
                status,
                "reason",
                "Test evidence")));
  }
}
