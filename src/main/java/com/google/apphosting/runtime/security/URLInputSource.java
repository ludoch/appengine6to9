
package com.google.apphosting.runtime.security;

import java.net.URL;
import java.io.InputStream;
import java.io.IOException;


public class URLInputSource implements InputSource {

  private final URL url;

  /**
   * Constructs a new InputSource with {@code url}.
   * 
   * @param url a non-null {@code URL}
   */
  public URLInputSource(URL url) {
    this.url = url;
  }

  @Override
  public InputStream getNewInputStream() throws IOException {
    return url.openStream();
  }
}
