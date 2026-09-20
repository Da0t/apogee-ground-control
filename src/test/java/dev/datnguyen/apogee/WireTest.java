package dev.datnguyen.apogee;

import static org.junit.jupiter.api.Assertions.*;

import dev.datnguyen.apogee.protocol.Wire;
import java.io.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireTest {
  @Test
  void multipleFramesAreParsedIndependently() throws Exception {
    var b = new ByteArrayOutputStream();
    Wire.write(b, Map.of("version", 1, "type", "QUERY"));
    Wire.write(b, Map.of("version", 1, "type", "ACK"));
    var in = new ByteArrayInputStream(b.toByteArray());
    assertEquals("QUERY", Wire.read(in).get("type").asText());
    assertEquals("ACK", Wire.read(in).get("type").asText());
    assertNull(Wire.read(in));
  }

  @Test
  void oversizeMalformedAndTruncatedFramesAreRejected() {
    assertThrows(
        IOException.class, () -> Wire.read(new ByteArrayInputStream(new byte[Wire.MAX_FRAME + 1])));
    assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream("{}\n".getBytes())));
    assertThrows(
        IOException.class, () -> Wire.read(new ByteArrayInputStream("{\"version\":1".getBytes())));
  }
}
