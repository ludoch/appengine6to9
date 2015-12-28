
package com.google.apphosting.runtime.jetty;

import javax.servlet.ServletContext;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.runtime.AppVersion;

/**
 * {@code JettyConstants} centralizes some constants that are specific
 * to our use of Jetty.
 *
 */
class JettyConstants {
  /**
   * This {@link ServletContext} attribute contains the {@link
   * AppVersion} for the current application.
   */
  public final static String APP_VERSION_CONTEXT_ATTR =
      "com.google.apphosting.runtime.jetty.APP_VERSION_CONTEXT_ATTR";

  /**
   * This {@code ServletRequest} attribute contains the {@link
   * AppVersionKey} identifying the current application.  identify
   * which application version to use.
   */
  public final static String APP_VERSION_KEY_REQUEST_ATTR =
      "com.google.apphosting.runtime.jetty.APP_VERSION_REQUEST_ATTR";
}
