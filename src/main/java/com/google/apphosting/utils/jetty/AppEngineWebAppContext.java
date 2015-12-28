
package com.google.apphosting.utils.jetty;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.LogRecord;

import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.util.URIUtil;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.File;

/**
 * {@code AppEngineWebAppContext} is a customization of Jetty's {@link
 * WebAppContext} that is aware of the {@link ApiProxy} and can
 * provide custom logging and authentication.
 *
 */
public class AppEngineWebAppContext extends WebAppContext {

  private static final int MAX_RESPONSE_SIZE = 32 * 1024 * 1024;

  private final String serverInfo;

  public AppEngineWebAppContext(String serverInfo) {
    this.serverInfo = serverInfo;
    init();
  }

  public AppEngineWebAppContext(File appDir, String serverInfo) {
    // We set the contextPath to / for all applications.
    super(appDir.getPath(), URIUtil.SLASH);

    this.serverInfo = serverInfo;
    init();
  }

  private void init() {
    // Override the default HttpServletContext implementation.
    _scontext = new AppEngineServletContext();

    // Configure the Jetty SecurityHandler to understand our method of
    // authentication (via the UserService).
    AppEngineAuthentication.configureSecurityHandler(getSecurityHandler());

    setMaxFormContentSize(MAX_RESPONSE_SIZE);
  }

  // N.B.(schwardo): Yuck.  Jetty hardcodes all of this logic into an
  // inner class of ContextHandler.  We need to subclass WebAppContext
  // (which extends ContextHandler) and then subclass the SContext
  // inner class to modify its behavior.
  public class AppEngineServletContext extends SContext {

    public ClassLoader getClassLoader() {
      return AppEngineWebAppContext.this.getClassLoader();
    }
    
    @Override
    public String getServerInfo() {
      return serverInfo;
    }

    @Override
    public void log(String message) {
      log(message, null);
    }

    /**
     * {@inheritDoc}
     *
     * @param throwable an exception associated with this log message,
     * or {@code null}.
     */
    @Override
    public void log(String message, Throwable throwable) {
      StringWriter writer = new StringWriter();
      writer.append("javax.servlet.ServletContext log: ");
      writer.append(message);

      if (throwable != null) {
        writer.append("\n");
        throwable.printStackTrace(new PrintWriter(writer));
      }

      LogRecord.Level logLevel = throwable == null ? LogRecord.Level.info : LogRecord.Level.error;
      ApiProxy.log(new ApiProxy.LogRecord(logLevel, System.currentTimeMillis() * 1000L, 
          writer.toString()));
    }

    @Override
    public void log(Exception exception, String msg) {
      log(msg, exception);
    }
  }
}
