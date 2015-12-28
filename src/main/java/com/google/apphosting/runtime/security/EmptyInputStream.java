
package com.google.apphosting.runtime.security;

import java.io.InputStream;
import java.io.IOException;


public class EmptyInputStream extends InputStream {
  
  public static final EmptyInputStream STREAM = new EmptyInputStream();

  @Override
  public int read() throws IOException {
    return -1;
  }
}
