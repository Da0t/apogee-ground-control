package dev.datnguyen.apogee;

import static dev.datnguyen.apogee.TestRig.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.domain.Models.*;
import dev.datnguyen.apogee.ground.PostgresStore;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  PostgresStore store;
  JdbcTemplate db;

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    db.execute("TRUNCATE commands,runs,events,telemetry RESTART IDENTITY CASCADE");
    store =
        new PostgresStore(db, new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  @Test
  void actualDatabaseRecoversUnknownAndReconciles() {
    var clock = new TestClock();
    var link = new TestLink();
    var engine = new GroundEngine(store, clock, link);
    telemetry(engine, 1, 82, "OFF");
    Run r = engine.start(UUID.randomUUID());
    engine.tick();
    Command c = store.commands(r.id).getFirst();
    var restarted = new GroundEngine(store, clock, link);
    restarted.recover();
    assertEquals(CommandStatus.UNKNOWN, store.command(c.id).status);
    assertEquals(RunStatus.PAUSED, store.runs().getFirst().status);
    restarted.reconcile(r.id);
    ack(restarted, c.id, "COMPLETED");
    telemetry(restarted, 2, 80, "READY");
    restarted.action(r.id, "resume");
    restarted.tick();
    assertEquals(1, store.runs().getFirst().step);
    assertFalse(store.events(r.id).isEmpty());
  }

  @Test
  void constraintPreventsTwoActiveRunsAndTransactionRollsBack() {
    Run r = new Run(UUID.randomUUID(), 1000);
    store.save(r);
    assertThrows(
        Exception.class,
        () ->
            store.atomic(
                () -> {
                  store.event(1000, "INFO", "must roll back", null, null);
                  store.save(new Run(UUID.randomUUID(), 1001));
                }));
    assertEquals(1, store.runs().size());
    assertTrue(store.events(null).isEmpty());
  }
}
