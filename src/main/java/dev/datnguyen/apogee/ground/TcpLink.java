package dev.datnguyen.apogee.ground;

import com.fasterxml.jackson.databind.JsonNode;
import dev.datnguyen.apogee.domain.GroundEngine;
import dev.datnguyen.apogee.protocol.Wire;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.function.Consumer;

public class TcpLink implements GroundEngine.Link, AutoCloseable {
  private final String host;
  private final int port;
  private volatile Socket socket;
  private volatile boolean running = true;
  private volatile long reconnectAfter;

  public TcpLink(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public void start(Consumer<JsonNode> receiver) {
    Thread.ofVirtual()
        .name("spacecraft-link")
        .start(
            () -> {
              while (running) {
                try {
                  if (System.currentTimeMillis() < reconnectAfter) {
                    Thread.sleep(100);
                    continue;
                  }
                  Socket next = new Socket();
                  next.connect(new InetSocketAddress(host, port), 1500);
                  next.setTcpNoDelay(true);
                  next.setSoTimeout(15000);
                  socket = next;
                  try (next) {
                    JsonNode n;
                    while (running && (n = Wire.read(next.getInputStream())) != null)
                      receiver.accept(n);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                } catch (IOException | RuntimeException e) {
                  /* reconnect; command deadlines represent uncertainty */
                } finally {
                  socket = null;
                }
                try {
                  Thread.sleep(500);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            });
  }

  public boolean connected() {
    Socket s = socket;
    return s != null && s.isConnected() && !s.isClosed();
  }

  public void send(Map<String, ?> m) {
    Socket s = socket;
    if (s == null) return;
    try {
      Wire.write(s.getOutputStream(), m);
    } catch (IOException e) {
      disconnect(0);
    }
  }

  public void disconnect(int seconds) {
    reconnectAfter = System.currentTimeMillis() + seconds * 1000L;
    Socket s = socket;
    if (s != null)
      try {
        s.close();
      } catch (IOException ignored) {
      }
  }

  public void close() {
    running = false;
    disconnect(0);
  }
}
