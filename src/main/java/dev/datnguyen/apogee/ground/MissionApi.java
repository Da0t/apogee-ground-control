package dev.datnguyen.apogee.ground;

import dev.datnguyen.apogee.domain.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class MissionApi {
  private final GroundEngine engine;
  private final TcpLink link;
  private final MissionStore store;
  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

  public MissionApi(GroundEngine engine, TcpLink link, MissionStore store) {
    this.engine = engine;
    this.link = link;
    this.store = store;
  }

  @GetMapping("/state")
  public Map<String, Object> state() {
    return engine.snapshot();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "project", "apogee");
  }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    if (clients.size() >= 20) throw new IllegalStateException("Too many live consoles");
    SseEmitter e = new SseEmitter(0L);
    clients.add(e);
    e.onCompletion(() -> clients.remove(e));
    e.onTimeout(() -> clients.remove(e));
    e.onError(t -> clients.remove(e));
    try {
      e.send(SseEmitter.event().name("state").data(engine.snapshot()));
    } catch (Exception ex) {
      clients.remove(e);
      e.complete();
    }
    return e;
  }

  public record StartRequest(
      UUID requestId, String procedureId, Integer version, Long notBefore, Long expiresAt) {}

  public record ProcedureRequest(
      String id, int baseVersion, String name, String description, List<Models.Step> steps) {}

  @PostMapping("/procedures")
  public Models.Procedure publish(@RequestBody ProcedureRequest request) {
    return engine.publish(
        request.id(),
        request.baseVersion(),
        request.name(),
        request.description(),
        request.steps());
  }

  @PostMapping("/contacts")
  public Contacts.Status contacts(@RequestBody Contacts.Plan plan) {
    return engine.configureContacts(plan);
  }

  public record FaultRequest(String mode) {}

  @PostMapping("/runs")
  public Models.Run start(@RequestBody StartRequest request) {
    if (request.requestId() == null) throw new IllegalArgumentException("requestId is required");
    return engine.start(
        request.requestId(),
        request.procedureId(),
        request.version(),
        request.notBefore(),
        request.expiresAt());
  }

  @PostMapping("/runs/{id}/{action}")
  public Map<String, Object> action(@PathVariable UUID id, @PathVariable String action) {
    if (action.equals("reconcile")) engine.reconcile(id);
    else engine.action(id, action);
    return state();
  }

  @PostMapping("/scenario")
  public Map<String, Object> fault(@RequestBody FaultRequest request) {
    engine.fault(request.mode());
    return state();
  }

  @PostMapping("/disconnect")
  public Map<String, Object> disconnect() {
    link.disconnect(15);
    return state();
  }

  @GetMapping("/runs/{id}/export")
  public ResponseEntity<?> export(@PathVariable UUID id) {
    var run = store.run(id);
    if (run == null) throw new IllegalArgumentException("Unknown run");
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=apogee-" + id + ".json")
        .body(
            Map.of(
                "run",
                run,
                "commands",
                store.commands(id),
                "events",
                store.events(id),
                "dataSource",
                "SIMULATED"));
  }

  @Scheduled(fixedDelay = 250)
  public void tick() {
    engine.tick();
  }

  @Scheduled(fixedDelay = 1000)
  public void publish() {
    var state = engine.snapshot();
    for (SseEmitter e : clients)
      try {
        e.send(SseEmitter.event().name("state").data(state));
      } catch (Exception ex) {
        clients.remove(e);
        e.complete();
      }
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<?> conflict(IllegalStateException e) {
    return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
  }
}
