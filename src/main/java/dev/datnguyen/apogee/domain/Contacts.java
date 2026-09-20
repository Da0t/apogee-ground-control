package dev.datnguyen.apogee.domain;

import java.util.*;

/** Accelerated, repeating communication windows; not an orbital propagator. */
public final class Contacts {
  public record Plan(
      boolean enabled,
      long epochMillis,
      int periodSeconds,
      int windowSeconds,
      String station,
      double latitude,
      double longitude) {}

  public record Window(long start, long end) {}

  public record Status(
      Plan plan, boolean open, double phase, long nextOpen, long nextClose, List<Window> windows) {}

  public static Plan defaultPlan() {
    return new Plan(false, 0, 120, 35, "ALPHA", 34, -118);
  }

  public static void validate(Plan p, long now) {
    if (p == null
        || p.station() == null
        || p.station().isBlank()
        || p.station().length() > 32
        || !Double.isFinite(p.latitude())
        || Math.abs(p.latitude()) > 85
        || !Double.isFinite(p.longitude())
        || Math.abs(p.longitude()) > 180
        || p.periodSeconds() < 30
        || p.periodSeconds() > 600
        || p.windowSeconds() < 5
        || p.windowSeconds() > p.periodSeconds() - 10
        || p.epochMillis() < 0
        || p.epochMillis() > now + 86_400_000L)
      throw new IllegalArgumentException(
          "Use a 30–600 second cycle, at least 5 seconds of contact and 10 seconds of blackout");
  }

  public static Status status(Plan p, long now) {
    long period = p.periodSeconds() * 1000L, length = p.windowSeconds() * 1000L;
    long cycle = Math.max(0, Math.floorDiv(now - p.epochMillis(), period));
    long start = p.epochMillis() + cycle * period;
    boolean within = now >= start && now < start + length;
    long next = within || now < start ? start : start + period;
    List<Window> windows = new ArrayList<>();
    for (int i = 0; i < 5; i++)
      windows.add(new Window(next + i * period, next + i * period + length));
    double phase = Math.floorMod(now - p.epochMillis() - length / 2, period) / (double) period;
    return new Status(p, !p.enabled() || within, phase, next, next + length, List.copyOf(windows));
  }
}
