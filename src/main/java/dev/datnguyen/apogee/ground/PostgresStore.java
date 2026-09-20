package dev.datnguyen.apogee.ground;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.domain.Models.*;
import dev.datnguyen.apogee.protocol.Wire;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresStore implements MissionStore {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public PostgresStore(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public void atomic(Runnable action) {
    tx.executeWithoutResult(s -> action.run());
  }

  private String json(Object value) {
    try {
      return Wire.JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return Wire.JSON.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public List<Run> runs() {
    return db.query(
        "SELECT document FROM runs ORDER BY created_at DESC LIMIT 100",
        (r, i) -> read(r.getString(1), Run.class));
  }

  public Run run(UUID id) {
    return db
        .query(
            "SELECT document FROM runs WHERE id=?", (r, i) -> read(r.getString(1), Run.class), id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Command> commands(UUID runId) {
    return db.query(
        "SELECT document FROM commands WHERE run_id=? ORDER BY created_at,id",
        (r, i) -> read(r.getString(1), Command.class),
        runId);
  }

  public Command command(UUID id) {
    return db
        .query(
            "SELECT document FROM commands WHERE id=?",
            (r, i) -> read(r.getString(1), Command.class),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void save(Run run) {
    db.update(
        "INSERT INTO runs VALUES (?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET"
            + " status=excluded.status,document=excluded.document",
        run.id,
        run.status.name(),
        run.createdAt,
        json(run));
  }

  public void save(Command c) {
    db.update(
        "INSERT INTO commands VALUES (?,?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET"
            + " status=excluded.status,document=excluded.document",
        c.id,
        c.runId,
        c.status.name(),
        c.createdAt,
        json(c));
  }

  public void event(long at, String level, String message, UUID runId, UUID commandId) {
    db.update(
        "INSERT INTO events(at,level,message,run_id,command_id) VALUES (?,?,?,?,?)",
        at,
        level,
        message,
        runId,
        commandId);
  }

  public List<Event> events(UUID runId) {
    String query =
        "SELECT * FROM events"
            + (runId == null ? "" : " WHERE run_id=?")
            + " ORDER BY sequence DESC LIMIT 250";
    return db.query(
        query,
        (r, i) ->
            new Event(
                r.getLong("sequence"),
                r.getLong("at"),
                r.getString("level"),
                r.getString("message"),
                r.getObject("run_id", UUID.class),
                r.getObject("command_id", UUID.class)),
        runId == null ? new Object[] {} : new Object[] {runId});
  }

  public void telemetry(Telemetry t) {
    db.update(
        "INSERT INTO telemetry(received_at,document) VALUES (?,?::jsonb)", t.receivedAt(), json(t));
    db.update(
        "DELETE FROM telemetry WHERE sequence < (SELECT COALESCE(MAX(sequence),0)-3600 FROM"
            + " telemetry)");
  }

  public List<Telemetry> samples() {
    return db.query(
        "SELECT document FROM (SELECT sequence,document FROM telemetry ORDER BY sequence DESC LIMIT"
            + " 90) recent ORDER BY sequence",
        (r, i) -> read(r.getString(1), Telemetry.class));
  }

  public List<Procedure> procedures() {
    return db.query(
        "SELECT document FROM procedures ORDER BY created_at DESC,id,version DESC LIMIT 300",
        (r, i) -> read(r.getString(1), Procedure.class));
  }

  public Procedure procedure(String id, Integer version) {
    return db
        .query(
            "SELECT document FROM procedures WHERE id=?"
                + (version == null ? "" : " AND version=?")
                + " ORDER BY version DESC LIMIT 1",
            (r, i) -> read(r.getString(1), Procedure.class),
            version == null ? new Object[] {id} : new Object[] {id, version})
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void save(Procedure p) {
    db.update(
        "INSERT INTO procedures(id,version,created_at,document) VALUES (?,?,?,?::jsonb)",
        p.id(),
        p.version(),
        p.createdAt(),
        json(p));
  }

  public Contacts.Plan contactPlan() {
    return db
        .query(
            "SELECT document FROM mission_settings WHERE id='contacts'",
            (r, i) -> read(r.getString(1), Contacts.Plan.class))
        .stream()
        .findFirst()
        .orElse(Contacts.defaultPlan());
  }

  public void save(Contacts.Plan p) {
    db.update(
        "INSERT INTO mission_settings VALUES ('contacts',?::jsonb) ON CONFLICT(id) DO UPDATE SET"
            + " document=excluded.document",
        json(p));
  }

  public Run activeRun() {
    return db
        .query(
            "SELECT document FROM runs WHERE status IN ('RUNNING','PAUSED','ABORTING') LIMIT 1",
            (r, i) -> read(r.getString(1), Run.class))
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Run> scheduledRuns() {
    return db.query(
        "SELECT document FROM runs WHERE status='SCHEDULED' ORDER BY"
            + " (document->>'notBefore')::bigint,created_at,id",
        (r, i) -> read(r.getString(1), Run.class));
  }
}
