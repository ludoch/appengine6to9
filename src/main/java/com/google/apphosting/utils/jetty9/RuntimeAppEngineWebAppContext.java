
package com.google.apphosting.utils.jetty9;

import java.io.File;

/**
 * The AppEngineWebAppContext for the Java runtime.
 **/

public class RuntimeAppEngineWebAppContext extends AppEngineWebAppContext {

  public RuntimeAppEngineWebAppContext(File appDir, String serverInfo) {
    super(appDir, serverInfo);
  }

  /**
   * Jetty needs a temp directory that already exists, so we point it to the directory of the war.
   * Since we don't allow Jetty to do any actual writes, this isn't a problem. It'd be nice to just
   * use setTempDirectory, but Jetty tests to see if it's writable.
   */
  @Override
  public File getTempDirectory() {
    return new File(getWar());
  }

  /**
   * A terrible hack. We lie to Jetty and return true here so that it doesn't
   * attempt to delete the temporary directory.
   *
   * @return
   */
  //@Override
  // public boolean isTempWorkDirectory() {
  //   return true;
  // }
  /* ------------------------------------------------------------ */

  /**
   * Set temporary directory for context. The javax.servlet.context.tempdir attribute is also
   * set.
   *
   * @param dir Writable temporary directory.
   */
  public void setTempDirectory(File dir) {

    if (dir != null && !dir.exists()) {
      dir.mkdir();
      //ludo not to be done ? dir.deleteOnExit();
    }
    super.setTempDirectory(dir);

  }
}
