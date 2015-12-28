
package com.google.apphosting.runtime.security;

import java.io.InputStream;
import java.io.ByteArrayInputStream;

/**
 * Implements an InputSource with a backing byte[]
*
*/
public class ByteArrayInputSource implements InputSource {
  private byte[] bytes;
  private int offset;
  private int length;

  public ByteArrayInputSource(byte[] bytes) {
    this.bytes = bytes;
    this.offset = 0;
    this.length = bytes.length;
  }

  public ByteArrayInputSource(byte[] bytes, int offset, int length) {
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
  }

  public InputStream getNewInputStream() {
    return new ByteArrayInputStream(bytes, offset, length);
  }
}
