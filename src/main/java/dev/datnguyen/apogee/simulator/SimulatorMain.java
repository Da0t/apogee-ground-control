package dev.datnguyen.apogee.simulator;

import dev.datnguyen.apogee.protocol.Wire;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class SimulatorMain {
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("SIMULATOR_PORT", "7071"));
    String bind = System.getenv().getOrDefault("SIMULATOR_BIND", "127.0.0.1");
    Spacecraft craft =
        new Spacecraft(
            Path.of(System.getenv().getOrDefault("SIMULATOR_STATE", "data/spacecraft.json")));
    Set<Socket> clients = ConcurrentHashMap.newKeySet();
    var timer = Executors.newSingleThreadScheduledExecutor();
    timer.scheduleAtFixedRate(
        () -> {
          try {
            var messages = new ArrayList<>(craft.tick());
            if (!craft.state().fault.equals("STALE_TELEMETRY")) messages.add(craft.telemetry());
            for (Socket s : clients)
              try {
                for (var message : messages) Wire.write(s.getOutputStream(), message);
              } catch (IOException e) {
                clients.remove(s);
                try {
                  s.close();
                } catch (IOException ignored) {
                }
              }
          } catch (Exception e) {
            System.err.println("Simulator cannot persist state; stopping: " + e.getMessage());
            System.exit(1);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    try (var server = new ServerSocket(port, 10, InetAddress.getByName(bind))) {
      System.out.println(
          "Apogee spacecraft simulator listening on "
              + bind
              + ":"
              + port
              + "; synthetic telemetry only");
      while (true) {
        Socket s = server.accept();
        s.setTcpNoDelay(true);
        s.setSoTimeout(60000);
        if (clients.size() >= 4) {
          s.close();
          continue;
        }
        clients.add(s);
        Thread.ofVirtual()
            .start(
                () -> {
                  try (s) {
                    if (!craft.state().fault.equals("STALE_TELEMETRY"))
                      Wire.write(s.getOutputStream(), craft.telemetry());
                    com.fasterxml.jackson.databind.JsonNode n;
                    while ((n = Wire.read(s.getInputStream())) != null) {
                      switch (n.path("type").asText()) {
                        case "COMMAND" ->
                            Wire.write(
                                s.getOutputStream(),
                                craft.command(
                                    n.path("commandId").asText(),
                                    n.path("kind").asText(),
                                    n.path("durationSeconds").asInt(0)));
                        case "QUERY" ->
                            Wire.write(
                                s.getOutputStream(), craft.query(n.path("commandId").asText()));
                        case "FAULT" -> craft.fault(n.path("mode").asText());
                        default -> throw new IOException("Unsupported message type");
                      }
                    }
                  } catch (Exception e) {
                    System.err.println("Link closed: " + e.getMessage());
                  } finally {
                    clients.remove(s);
                  }
                });
      }
    } finally {
      timer.shutdownNow();
    }
  }
}
