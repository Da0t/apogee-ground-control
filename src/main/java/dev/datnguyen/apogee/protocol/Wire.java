package dev.datnguyen.apogee.protocol;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.Map;

/** UTF-8 JSON objects, newline framed, bounded to 16 KiB per message. */
public final class Wire {
  public static final ObjectMapper JSON = new ObjectMapper();
  public static final int MAX_FRAME = 16 * 1024;

  private Wire() {}

  public static JsonNode read(InputStream input) throws IOException {
    var bytes = new ByteArrayOutputStream();
    int b;
    while ((b = input.read()) != -1) {
      if (b == '\n') {
        JsonNode n = JSON.readTree(bytes.toByteArray());
        if (n == null
            || !n.isObject()
            || n.path("version").asInt() != 1
            || !n.path("type").isTextual()) throw new IOException("Invalid protocol envelope");
        return n;
      }
      if (bytes.size() >= MAX_FRAME) throw new IOException("Frame exceeds 16 KiB");
      bytes.write(b);
    }
    if (bytes.size() > 0) throw new EOFException("Truncated frame");
    return null;
  }

  public static void write(OutputStream out, Map<String, ?> message) throws IOException {
    synchronized (out) {
      byte[] b = JSON.writeValueAsBytes(message);
      if (b.length > MAX_FRAME) throw new IOException("Frame too large");
      out.write(b);
      out.write('\n');
      out.flush();
    }
  }
}
