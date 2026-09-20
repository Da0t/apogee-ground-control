package dev.datnguyen.apogee;

import static org.junit.jupiter.api.Assertions.*;

import dev.datnguyen.apogee.simulator.Spacecraft;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SpacecraftTest {
  @TempDir Path directory;
  Spacecraft craft;

  @BeforeEach
  void setup() throws Exception {
    craft = new Spacecraft(directory.resolve("state.json"));
  }

  void ready() throws Exception {
    craft.command(UUID.randomUUID().toString(), "POWER_ON");
    craft.tick();
    craft.tick();
  }

  @Test
  void duplicateSurvivesRestartWithoutRepeatingObservation() throws Exception {
    ready();
    String id = UUID.randomUUID().toString();
    craft.command(id, "CAPTURE");
    for (int i = 0; i < 6; i++) craft.tick();
    craft = new Spacecraft(directory.resolve("state.json"));
    assertEquals("COMPLETED", craft.command(id, "CAPTURE").get("status"));
    for (int i = 0; i < 6; i++) craft.tick();
    assertEquals(1, craft.state().observations);
    assertEquals(32, craft.state().storage);
  }

  @Test
  void pendingCommandResumesAfterSimulatorRestart() throws Exception {
    ready();
    String id = UUID.randomUUID().toString();
    craft.command(id, "CAPTURE");
    craft.tick();
    craft = new Spacecraft(directory.resolve("state.json"));
    for (int i = 0; i < 5; i++) craft.tick();
    assertEquals("COMPLETED", craft.query(id).get("status"));
    assertEquals(1, craft.state().observations);
  }

  @Test
  void droppedAckStillHasDurableCompletionEvidence() throws Exception {
    ready();
    craft.fault("DROP_COMPLETION");
    String id = UUID.randomUUID().toString();
    craft.command(id, "CAPTURE");
    for (int i = 0; i < 6; i++) assertTrue(craft.tick().isEmpty());
    assertEquals("COMPLETED", craft.query(id).get("status"));
  }

  @Test
  void spacecraftIndependentlyRejectsUnsafeCommands() throws Exception {
    craft.fault("LOW_BATTERY");
    assertEquals("REJECTED", craft.command(UUID.randomUUID().toString(), "POWER_ON").get("status"));
    assertEquals("OFF", craft.state().instrument);
  }

  @Test
  void reusedIdWithDifferentCommandIsRejected() throws Exception {
    String id = UUID.randomUUID().toString();
    craft.command(id, "POWER_ON");
    assertThrows(IllegalArgumentException.class, () -> craft.command(id, "CAPTURE"));
  }

  @Test
  void collectionDurationIsPersistedAndPartOfCommandIdentity() throws Exception {
    ready();
    String id = UUID.randomUUID().toString();
    craft.command(id, "CAPTURE", 3);
    craft.tick();
    craft = new Spacecraft(directory.resolve("state.json"));
    assertThrows(IllegalArgumentException.class, () -> craft.command(id, "CAPTURE", 4));
    craft.tick();
    assertEquals("ACCEPTED", craft.query(id).get("status"));
    craft.tick();
    assertEquals("COMPLETED", craft.command(id, "CAPTURE", 3).get("status"));
    assertEquals(1, craft.state().observations);
  }
}
