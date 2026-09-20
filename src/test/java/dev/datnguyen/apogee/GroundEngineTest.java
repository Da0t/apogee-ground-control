package dev.datnguyen.apogee;

import static dev.datnguyen.apogee.TestRig.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.domain.Models.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GroundEngineTest {
  MemoryStore store;
  TestClock clock;
  TestLink link;
  GroundEngine engine;

  @BeforeEach
  void setup() {
    store = new MemoryStore();
    clock = new TestClock();
    link = new TestLink();
    engine = new GroundEngine(store, clock, link);
    telemetry(engine, 1, 82, "OFF");
  }

  Run start() {
    return engine.start(UUID.randomUUID());
  }

  Command current(Run r) {
    return store.command(store.runs().getFirst().commandId);
  }

  @Test
  void rejectsStaleTelemetry() {
    clock.advance(5001);
    assertThrows(IllegalStateException.class, this::start);
    assertTrue(store.runs().isEmpty());
  }

  @Test
  void rejectsLowBattery() {
    telemetry(engine, 2, 15, "OFF");
    assertThrows(IllegalStateException.class, this::start);
  }

  @Test
  void onlyOneProcedureReservesInstrument() {
    Run r = start();
    assertEquals(r.id, engine.start(r.id).id);
    assertThrows(IllegalStateException.class, this::start);
  }

  @Test
  void acceptedIsNotCompleted() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    ack(engine, c.id, "ACCEPTED");
    engine.tick();
    assertEquals(0, store.runs().getFirst().step);
    assertEquals(1, link.messages.size());
  }

  @Test
  void lostCompletionRequiresQueryAndExplicitResume() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    ack(engine, c.id, "ACCEPTED");
    clock.advance(12001);
    engine.tick();
    assertEquals(CommandStatus.UNKNOWN, store.command(c.id).status);
    assertEquals(RunStatus.PAUSED, store.runs().getFirst().status);
    assertThrows(IllegalStateException.class, () -> engine.action(r.id, "resume"));
    engine.reconcile(r.id);
    assertEquals("QUERY", link.messages.getLast().get("type"));
    ack(engine, c.id, "COMPLETED");
    assertEquals(RunStatus.PAUSED, store.runs().getFirst().status);
    telemetry(engine, 2, 80, "READY");
    engine.action(r.id, "resume");
    engine.tick();
    assertEquals(1, store.runs().getFirst().step);
  }

  @Test
  void processRestartDoesNotResend() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    GroundEngine restarted = new GroundEngine(store, clock, link);
    restarted.recover();
    restarted.tick();
    assertEquals(CommandStatus.UNKNOWN, store.command(c.id).status);
    assertEquals(1, link.messages.size());
  }

  @Test
  void abortWaitsForInflightAndPreservesReservation() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    engine.action(r.id, "abort");
    assertEquals(RunStatus.ABORTING, store.runs().getFirst().status);
    assertThrows(IllegalStateException.class, this::start);
    ack(engine, c.id, "COMPLETED");
    engine.tick();
    assertEquals(RunStatus.ABORTED, store.runs().getFirst().status);
    assertEquals(1, link.messages.size());
  }

  @Test
  void terminalOutcomeCannotRegress() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    ack(engine, c.id, "COMPLETED");
    ack(engine, c.id, "ACCEPTED");
    assertEquals(CommandStatus.COMPLETED, store.command(c.id).status);
  }

  @Test
  void oldTelemetryCannotRefreshFreshness() {
    clock.advance(6000);
    telemetry(engine, 1, 82, "OFF");
    assertThrows(IllegalStateException.class, this::start);
  }

  @Test
  void completeProcedureAdvancesOnlyOnEvidence() {
    Run r = start();
    long seq = 2;
    for (int i = 0; i < 3; i++) {
      telemetry(engine, seq++, 82, i == 0 ? "OFF" : "READY");
      engine.tick();
      Command c = current(r);
      assertEquals(Models.PROCEDURE.get(i), c.kind);
      ack(engine, c.id, "COMPLETED");
      engine.tick();
    }
    assertEquals(RunStatus.COMPLETED, store.runs().getFirst().status);
    assertEquals(3, link.messages.size());
  }

  @Test
  void unknownLedgerEntryDoesNotAuthorizeRetry() {
    Run r = start();
    engine.tick();
    Command c = current(r);
    clock.advance(12001);
    engine.tick();
    ack(engine, c.id, "NOT_FOUND");
    engine.tick();
    assertEquals(CommandStatus.UNKNOWN, store.command(c.id).status);
    assertEquals(1, link.messages.size());
  }
}
