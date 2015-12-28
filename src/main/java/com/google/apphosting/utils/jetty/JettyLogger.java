// Copyright 2011 Google Inc. All rights reserved.

package com.google.apphosting.utils.jetty;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.LogRecord;

import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.log.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * {@code JettyLogger} is a extension for {@link org.eclipse.jetty.util.log.JavaUtilLog}
 *
 */
public class JettyLogger extends JavaUtilLog {

  private static boolean logToApiProxy = Boolean.getBoolean("appengine.jetty.also_log_to_apiproxy");


  public JettyLogger() {
    this(null);
  }

  public JettyLogger(String name) {
    super("JettyLogger(" + name + ")");
  }


  public void warn(String msg, Throwable th) {
    super.warn(msg, th);

    // N.B.(schwardo): There are a number of cases where Jetty
    // swallows exceptions entirely, or at least stashes them away in
    // private fields.  To avoid these situations, we log all warning
    // exceptions to the user's app logs via ApiProxy, as long as we
    // have an environment set up.
    //
    // Note that we also only do this if there is a Throwable
    // provided.  Jetty logs some things that aren't very useful, and
    // we're really only worried that stack traces are preserved here.
    if (logToApiProxy && ApiProxy.getCurrentEnvironment() != null && th != null) {
      ApiProxy.log(createLogRecord(msg, th));
    }
  }

  /**
   * Create a Child Logger of this Logger.
   */
  @Override
  protected Logger newLogger(String name) {
    return new JettyLogger(name);
  }

  public boolean isDebugEnabled() {
    return false; //logger.getLevel() == Level.FINEST;
  }

  public String toString() {
    return getName();
  }

  private LogRecord createLogRecord(String message, Throwable ex) {
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    printWriter.println(message);
    if (ex != null) {
      ex.printStackTrace(printWriter);
    }

    return new LogRecord(LogRecord.Level.warn,
        System.currentTimeMillis() * 1000,
        stringWriter.toString());
  }
}
