package com.google.archivepatcher.generator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class MinimalZipParserRegressionTest {
  @Test public void truncatedExactReadReportsIOException() throws Exception {
    Method method = MinimalZipParser.class.getDeclaredMethod(
        "readOrDie", java.io.InputStream.class, byte[].class, int.class, int.class);
    method.setAccessible(true);
    try {
      method.invoke(null, new ByteArrayInputStream(new byte[] {1}), new byte[4], 0, 4);
      fail("Expected truncation");
    } catch (InvocationTargetException error) {
      assertTrue(error.getCause() instanceof IOException);
    }
  }
}
