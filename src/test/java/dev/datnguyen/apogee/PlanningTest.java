package dev.datnguyen.apogee;

import static dev.datnguyen.apogee.TestRig.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.domain.Models.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PlanningTest {
  MemoryStore store = new MemoryStore();
  TestClock clock = new TestClock();
  TestLink link = new TestLink();
  GroundEngine engine = new GroundEngine(store, clock, link);

  @BeforeEach
  void connected() {
    telemetry(engine, 1, 82, "OFF");
  }

  @Test
  void publishedVersionCannotChangeAnExistingRun() {
    Run run = engine.start(UUID.randomUUID());
    var original = Models.defaultProcedure();
    var revised = new ArrayList<>(original.steps());
    revised.set(1, new Step(CommandKind.CAPTURE, 10, 15, 40, 60));
    engine.publish(original.id(), 1, "Long exposure", "A revision", revised);
    assertEquals(1, store.run(run.id).definition.version());
    assertEquals(6, store.run(run.id).definition.steps().get(1).durationSeconds());
    assertEquals(10, store.procedure(original.id(), 2).steps().get(1).durationSeconds());
    assertThrows(
        IllegalStateException.class,
        () -> engine.publish(original.id(), 1, "Stale edit", "", revised));
  }

  @Test
  void invalidSequenceAndUnsafeGuardsAreRejected() {
    var off = Models.defaultProcedure().steps().getLast();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.publish(
                "BAD-PLAN",
                0,
                "Invalid",
                "",
                List.of(new Step(CommandKind.CAPTURE, 6, 12, 30, 80), off)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.publish(
                "BAD-PLAN",
                0,
                "Invalid",
                "",
                List.of(new Step(CommandKind.POWER_ON, 2, 12, 10, 80), off)));
    assertTrue(store.procedures().isEmpty());
  }

  @Test
  void commandUsesThePublishedVerificationDeadline() {
    engine.publish(
        "QUICK-CHECK",
        0,
        "Power cycle",
        "",
        List.of(
            new Step(CommandKind.POWER_ON, 2, 4, 30, 80),
            Models.defaultProcedure().steps().getLast()));
    var run = engine.start(UUID.randomUUID(), "QUICK-CHECK", 1, null, null);
    engine.tick();
    clock.advance(4001);
    engine.tick();
    assertEquals(CommandStatus.UNKNOWN, store.commands(run.id).getFirst().status);
    assertEquals(RunStatus.PAUSED, store.run(run.id).status);
  }

  @Test
  void scheduledRunWaitsForTimeContactAndNewTelemetry() {
    engine.configureContacts(
        new Contacts.Plan(true, clock.millis() + 10000, 30, 8, "ALPHA", 34, -118));
    var run =
        engine.start(UUID.randomUUID(), null, 1, clock.millis() + 5000, clock.millis() + 20000);
    clock.advance(6000);
    telemetry(engine, 2, 82, "OFF"); // rejected outside contact
    engine.tick();
    assertEquals(RunStatus.SCHEDULED, store.run(run.id).status);
    assertTrue(link.messages.isEmpty());
    clock.advance(4000);
    engine.tick();
    assertTrue(link.messages.isEmpty()); // contact alone is insufficient
    telemetry(engine, 3, 82, "OFF");
    engine.tick();
    assertEquals(RunStatus.RUNNING, store.run(run.id).status);
    assertEquals(1, link.messages.size());
  }

  @Test
  void missedStartDeadlineNeverDispatches() {
    var run =
        engine.start(UUID.randomUUID(), null, 1, clock.millis() + 1000, clock.millis() + 2000);
    clock.advance(2500);
    engine.tick();
    assertEquals(RunStatus.FAILED, store.run(run.id).status);
    assertTrue(store.commands(run.id).isEmpty());
  }

  @Test
  void schedulerActivatesOnlyOneRunAndAbortReleasesIt() {
    var first = engine.start(UUID.randomUUID(), null, 1, clock.millis(), clock.millis() + 20000);
    var second = engine.start(UUID.randomUUID(), null, 1, clock.millis(), clock.millis() + 20000);
    engine.tick();
    assertEquals(RunStatus.RUNNING, store.run(first.id).status);
    assertEquals(RunStatus.SCHEDULED, store.run(second.id).status);
    engine.action(second.id, "abort");
    assertEquals(RunStatus.ABORTED, store.run(second.id).status);
    assertEquals(1, link.messages.size());
  }

  @Test
  void contactLossPreservesUncertaintyAndRequiresExplicitRecovery() {
    engine.configureContacts(new Contacts.Plan(true, clock.millis(), 30, 5, "ALPHA", 34, -118));
    telemetry(engine, 2, 82, "OFF");
    var run = engine.start(UUID.randomUUID());
    engine.tick();
    var command = store.commands(run.id).getFirst();
    clock.advance(6000);
    ack(engine, command.id, "COMPLETED"); // remote completion is not received outside contact
    clock.advance(7000);
    engine.tick();
    assertEquals(CommandStatus.UNKNOWN, store.command(command.id).status);
    assertThrows(IllegalStateException.class, () -> engine.reconcile(run.id));
    clock.advance(17000);
    telemetry(engine, 3, 82, "READY");
    engine.reconcile(run.id);
    ack(engine, command.id, "COMPLETED");
    assertEquals(RunStatus.PAUSED, store.run(run.id).status);
    assertEquals(2, link.messages.size()); // original command plus QUERY, never resend
  }

  @Test
  void scheduledDefinitionAndPlanSurviveRecovery() {
    var plan = new Contacts.Plan(true, clock.millis() + 10000, 30, 8, "ALPHA", 34, -118);
    engine.configureContacts(plan);
    var run =
        engine.start(UUID.randomUUID(), null, 1, clock.millis() + 1000, clock.millis() + 20000);
    new GroundEngine(store, clock, link).recover();
    assertEquals(RunStatus.SCHEDULED, store.run(run.id).status);
    assertEquals(plan, store.contactPlan());
    assertEquals(1, store.run(run.id).definition.version());
  }

  @Test
  void contactWindowsHaveExclusiveEndsAndRepeatAcrossRestart() {
    var plan = new Contacts.Plan(true, 100000, 30, 8, "ALPHA", 34, -118);
    assertFalse(Contacts.status(plan, 99999).open());
    assertTrue(Contacts.status(plan, 100000).open());
    assertFalse(Contacts.status(plan, 108000).open());
    assertEquals(130000, Contacts.status(plan, 108000).nextOpen());
    assertTrue(Contacts.status(plan, 130000).open());
  }

  @Test
  void repeatedCollectionsKeepIndependentStepIdentity() {
    var defaults = Models.defaultProcedure().steps();
    engine.publish(
        "PAIR-001",
        0,
        "Two observations",
        "",
        List.of(
            defaults.getFirst(),
            new Step(CommandKind.CAPTURE, 3, 8, 30, 80),
            new Step(CommandKind.CAPTURE, 4, 8, 30, 80),
            defaults.getLast()));
    var run = engine.start(UUID.randomUUID(), "PAIR-001", 1, null, null);
    for (int i = 0; i < 4; i++) {
      telemetry(engine, i + 2, 82, i == 0 ? "OFF" : "READY");
      engine.tick();
      var command = store.commands(run.id).getLast();
      assertEquals(i, command.stepIndex);
      ack(engine, command.id, "COMPLETED");
      engine.tick();
    }
    assertEquals(RunStatus.COMPLETED, store.run(run.id).status);
    assertEquals(4, store.commands(run.id).stream().map(c -> c.id).distinct().count());
    assertEquals(3, store.commands(run.id).get(1).durationSeconds);
    assertEquals(4, store.commands(run.id).get(2).durationSeconds);
  }
}
