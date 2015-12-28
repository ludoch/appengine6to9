
package com.google.apphosting.runtime.security;

import java.io.InputStream;
import java.io.IOException;

/**
 * A factory for an InputStream.
*
*/
public interface InputSource {

  /**
   * Supplies a new InputStream seated at the beginning of the resource. 
   * 
   * @return a new InputStream
   * @throws IOException if a new InputStream could not be created.
   */
  InputStream getNewInputStream() throws IOException;
}
