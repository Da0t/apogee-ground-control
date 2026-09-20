package dev.datnguyen.apogee.domain;

import dev.datnguyen.apogee.domain.Models.*;
import java.util.*;

public interface MissionStore {
  void atomic(Runnable action);

  List<Run> runs();

  Run run(UUID id);

  List<Command> commands(UUID runId);

  Command command(UUID id);

  void save(Run run);

  void save(Command command);

  void event(long at, String level, String message, UUID runId, UUID commandId);

  List<Event> events(UUID runId);

  void telemetry(Telemetry telemetry);

  List<Telemetry> samples();
}
