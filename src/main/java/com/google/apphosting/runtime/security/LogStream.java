
package com.google.apphosting.runtime.security;

import java.io.OutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.nio.charset.Charset;

/**
 * Writes its contents to a {@link Logger}. Writes against the 
 * {@code LogStream} are only written to the {@code Logger} on
 * an explicit call to flush.
 *
 */
public class LogStream extends OutputStream {
  
  private Logger logger;
  private Level level;
  private Charset utf8 = Charset.forName("UTF-8");
  private ByteArrayOutputStream output = new ByteArrayOutputStream(4 * 1024);

  /**
  * Creates a new LogStream that logs to {@code logger} with messages
  * at Level, {@code level}. 
  *
  * @param level The level to log at
  */
  public LogStream(Level level) {
    this.level = level;
  }

  public void setLogger(Logger logger) {
    this.logger = logger;
  }
  
  public void write(int b) throws IOException {
    output.write(b);
  }

  public void write(byte b[]) throws IOException {
    output.write(b);
  }

  public void write(byte b[], int off, int len) throws IOException {
    output.write(b, off, len);
  }

  public void flush() throws IOException {
    byte[] buffer = output.toByteArray();
    if (logger == null || buffer.length == 0) {
      return;
    }
    String msg = new String(buffer, 0, buffer.length, utf8);
    LogRecord record = new LogRecord(level, msg);
    record.setSourceClassName(null);
    record.setSourceMethodName(null);
    record.setLoggerName(logger.getName());
    logger.log(record);
    output.reset();
  }
}
