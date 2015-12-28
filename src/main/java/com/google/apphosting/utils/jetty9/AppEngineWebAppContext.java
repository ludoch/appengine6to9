// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.apphosting.utils.jetty9;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.LogRecord;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * {@code AppEngineWebAppContext} is a customization of Jetty's {@link
 * WebAppContext} that is aware of the {@link ApiProxy} and can
 * provide custom logging and authentication.
 *
 */

// org.eclipse.jetty.server.handler.ContextHandler.Context has unchecked conversions.
// We inherit the warnings (even though we do not override the problematic methods.
// Supressing warnings to remove warning spam.
@SuppressWarnings("unchecked")
public class AppEngineWebAppContext extends WebAppContext {
  // TODO(schwardo): This should be some sort of Prometheus-wide
  // constant.  If it's much larger than this we may need to
  // restructure the code a bit.
  protected static final int MAX_RESPONSE_SIZE = 32 * 1024 * 1024;

  private final String serverInfo;

  public AppEngineWebAppContext(String serverInfo) {
    this.serverInfo = serverInfo;
    init();
  }

  public AppEngineWebAppContext(File appDir, String serverInfo) {
    // We set the contextPath to / for all applications.
    super(appDir.getPath(), URIUtil.SLASH);
    Resource webApp = null;
    try {
      webApp = Resource.newResource(appDir.getAbsolutePath());
      if (appDir.isDirectory()) {
        setWar(appDir.getPath());
        setBaseResource(webApp);
      } else { // real war file, not exploded , so we explode it in tmp area
        File extractedWebAppDir = createTempDir();
        extractedWebAppDir.mkdir();
        extractedWebAppDir.deleteOnExit();
        Resource jarWebWpp = JarResource.newJarResource(webApp);
        jarWebWpp.copyTo(extractedWebAppDir);
        setBaseResource(Resource.newResource(extractedWebAppDir.getAbsolutePath()));
        setWar(extractedWebAppDir.getPath());
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot create AppEngineWebAppContext:", e);
    }

    this.serverInfo = serverInfo;
    init();
  }

  private void init() {
    // Override the default HttpServletContext implementation.
    _scontext = new AppEngineServletContext();

    // Configure the Jetty SecurityHandler to understand our method of authentication
    // (via the UserService). Only the default ConstraintSecurityHandler is supported.
    AppEngineAuthentication.configureSecurityHandler(
        (ConstraintSecurityHandler) getSecurityHandler());

    setMaxFormContentSize(MAX_RESPONSE_SIZE);
  }

  private static File createTempDir() {
    File baseDir = new File(System.getProperty("java.io.tmpdir"));
    String baseName = System.currentTimeMillis() + "-";

    for (int counter = 0; counter < 10; counter++) {
      File tempDir = new File(baseDir, baseName + counter);
      if (tempDir.mkdir()) {
        return tempDir;
      }
    }
    throw new IllegalStateException("Failed to create directory ");
  }

  // N.B.(schwardo): Yuck. Jetty hardcodes all of this logic into an
  // inner class of ContextHandler. We need to subclass WebAppContext
  // (which extends ContextHandler) and then subclass the SContext
  // inner class to modify its behavior.
  public class AppEngineServletContext extends Context {

    @Override
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
