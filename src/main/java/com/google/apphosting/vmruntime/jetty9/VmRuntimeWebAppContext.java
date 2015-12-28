package com.google.apphosting.vmruntime.jetty9;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.session.AbstractSessionManager;

import com.google.appengine.spi.ServiceFactoryFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.AppEngineWebXmlReader;
import com.google.apphosting.utils.jetty.StubSessionManager;
import com.google.apphosting.utils.jetty9.AppEngineWebAppContext;
import com.google.apphosting.vmruntime.CommitDelayingResponseServlet3;
import com.google.apphosting.vmruntime.VmApiProxyDelegate;
import com.google.apphosting.vmruntime.VmApiProxyEnvironment;
import com.google.apphosting.vmruntime.VmMetadataCache;
import com.google.apphosting.vmruntime.VmRuntimeLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

/**
 * WebAppContext for VM Runtimes. This class extends the "normal" AppEngineWebAppContext with
 * functionality that installs a request specific thread local environment on each incoming request.
 */
public class VmRuntimeWebAppContext extends AppEngineWebAppContext {
  private static final Logger logger = Logger.getLogger(VmRuntimeWebAppContext.class.getName());

  // It's undesirable to have the user app override classes provided by us.
  // So we mark them as Jetty system classes, which cannot be overridden.
  private static final String[] SYSTEM_CLASSES = {
    // The trailing dot means these are all Java packages, not individual classes.
    "com.google.appengine.api.",
  };

  private final VmMetadataCache metadataCache;
 // private final Timer wallclockTimer;
  private VmApiProxyEnvironment defaultEnvironment;
  static {
    // Set SPI classloader priority to prefer the WebAppClassloader.
    System.setProperty(
        ServiceFactoryFactory.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
    // Use thread context class loader for memcache deserialization.
  //  System.setProperty(
   //     MemcacheSerialization.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
  }

  private static final String HEALTH_CHECK_PATH = "/_ah/health";
  private static boolean isLastSuccessful = false;
  // The time stamp of last normal health check, in milliseconds.
  private static long timeStampOfLastNormalCheckMillis = 0;
  @VisibleForTesting
  static int checkIntervalSec = -1;
  static final int DEFAULT_CHECK_INTERVAL_SEC = 5;
  static final String VIRTUAL_PEER_IP = "169.254.160.2";
  static final String SDK_SOURCE_IP = "10.0.2.2";

  /**
   * Creates a List of SessionStores based on the configuration in the provided AppEngineWebXml.
   *
   * @param appEngineWebXml The AppEngineWebXml containing the session configuration.
   * @return A List of SessionStores in write order.
   */
//  private static List<SessionStore> createSessionStores(AppEngineWebXml appEngineWebXml) {
//    DatastoreSessionStore datastoreSessionStore =
//        appEngineWebXml.getAsyncSessionPersistence() ? new DeferredDatastoreSessionStore(
//            appEngineWebXml.getAsyncSessionPersistenceQueueName())
//            : new DatastoreSessionStore();
//    // Write session data to the datastore before we write to memcache.
//    return Arrays.asList(datastoreSessionStore, new MemcacheSessionStore());
//  }


  /**
   * Checks if the request was made over HTTPS. If so it modifies the request so that
   * {@code HttpServletRequest#isSecure()} returns true, {@code HttpServletRequest#getScheme()}
   * returns "https", and {@code HttpServletRequest#getServerPort()} returns 443. Otherwise it sets
   * the scheme to "http" and port to 80.
   *
   * @param request The request to modify.
   */
  private static void setSchemeAndPort(Request request) {
    String https = request.getHeader(VmApiProxyEnvironment.HTTPS_HEADER);
////////    if ("on".equals(https)) {
////////      request.setSecure(true);
////////      request.setScheme(HttpScheme.HTTPS.toString());
////////      request.setServerPort(443);
////////    } else {
////////      request.setSecure(false);
////////      request.setScheme(HttpScheme.HTTP.toString());
////////      request.setServerPort(80);
////////    }
  }

  /**
   * Creates a new VmRuntimeWebAppContext.
   */
  public VmRuntimeWebAppContext() {
    super(VmRuntimeUtils.getServerInfo());
    metadataCache = new VmMetadataCache();
 //   wallclockTimer = new VmTimer();
    ApiProxy.setDelegate(new VmApiProxyDelegate());
  }

  /**
   * Initialize the WebAppContext for use by the VmRuntime.
   *
   * This method initializes the WebAppContext by setting the context path and application folder.
   * It will also parse the appengine-web.xml file provided to set System Properties and session
   * manager accordingly.
   *
   * @param appDir The war directory of the application.
   * @param appengineWebXmlFile The appengine-web.xml file path (relative to appDir).
   * @throws AppEngineConfigException If there was a problem finding or parsing the
   *         appengine-web.xml configuration.
   * @throws IOException If the runtime was unable to find/read appDir.
   */
  public void init(String appDir, String appengineWebXmlFile)
      throws AppEngineConfigException, IOException {
    setContextPath("/");
    setWar(appDir);
    setResourceBase(appDir);
    defaultEnvironment = VmApiProxyEnvironment.createDefaultContext(
        System.getenv(), metadataCache, VmRuntimeUtils.getApiServerAddress(), null,
        VmRuntimeUtils.ONE_DAY_IN_MILLIS, new File(appDir).getCanonicalPath());
    ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    AppEngineWebXmlReader appEngineWebXmlReader =
        new AppEngineWebXmlReader(appDir, appengineWebXmlFile);
    AppEngineWebXml appEngineWebXml = appEngineWebXmlReader.readAppEngineWebXml();
    VmRuntimeUtils.installSystemProperties(defaultEnvironment, appEngineWebXml);
    VmRuntimeLogHandler.init();

    for (String systemClass: SYSTEM_CLASSES) {
      addSystemClass(systemClass);
    }

    AbstractSessionManager sessionManager;
    if (appEngineWebXml.getSessionsEnabled()) {
  //////    sessionManager = new SessionManager(createSessionStores(appEngineWebXml));
    } else {
      sessionManager = new StubSessionManager();
    }
  ////  setSessionHandler(new SessionHandler(sessionManager));

    // Get check interval second(s) to be used by special health check handler.
    checkIntervalSec = Objects.firstNonNull(
        appEngineWebXml.getVmHealthCheck().getCheckIntervalSec(), DEFAULT_CHECK_INTERVAL_SEC);
    if (checkIntervalSec <= 0) {
      logger.warning(
          "health check interval is not positive: " + checkIntervalSec +
          ". Using default value: " + DEFAULT_CHECK_INTERVAL_SEC);
      checkIntervalSec = DEFAULT_CHECK_INTERVAL_SEC;
    }
  }

  /**
   * Checks if a remote address is trusted for the purposes of handling requests.
   *
   * @param remoteAddr String representation of the remote ip address.
   * @returns True if and only if the remote address should be allowed to make requests.
   */
  public static final boolean isValidRemoteAddr(String remoteAddr) {
    // Allow traffic from default docker ip bride range (172.17.0.0/16)
    if (remoteAddr.startsWith("172.17.")) {
      return true;
    }
    // Allow the virtual peer IP.
    if (VIRTUAL_PEER_IP.equals(remoteAddr)) {
      return true;
    }
    // Needed for SDK when app clones run inside of docker containers that run
    // inside of VirtualBox. In NAT mode guest is assigned to 10.0.2.15,
    // gateway - 10.0.2.2, nameserver - 10.0.2.3.
    if (SDK_SOURCE_IP.equals(remoteAddr)) {
      return true;
    }
    // Allow localhost.
    if (remoteAddr.startsWith("127.0.0.")) {
      return true;
    }
    return false;
  }

  private static boolean isHealthCheck(HttpServletRequest request) {
    if (HEALTH_CHECK_PATH.equalsIgnoreCase(request.getPathInfo())) {
      return true;
    }
    return false;
  }

  private static boolean isLocalHealthCheck(HttpServletRequest request, String remoteAddr) {
    String isLastSuccessfulPara = request.getParameter("IsLastSuccessful");
    if (isLastSuccessfulPara == null && !VIRTUAL_PEER_IP.equals(remoteAddr)) {
      return true;
    }
    return false;
  }

  /**
   * Record last normal health check status. It sets this.isLastSuccessful based on the value of
   * "IsLastSuccessful" parameter from the query string ("yes" for True, otherwise False), and also
   * updates this.timeStampOfLastNormalCheckMillis.
   *
   * @param request the HttpServletRequest
   */
  private static void recordLastNormalHealthCheckStatus(HttpServletRequest request) {
    String isLastSuccessfulPara = request.getParameter("IsLastSuccessful");
    if ("yes".equalsIgnoreCase(isLastSuccessfulPara)) {
      isLastSuccessful = true;
    } else if ("no".equalsIgnoreCase(isLastSuccessfulPara)) {
      isLastSuccessful = false;
    } else {
      isLastSuccessful = false;
      logger.warning("Wrong parameter for IsLastSuccessful: " + isLastSuccessfulPara);
    }

    timeStampOfLastNormalCheckMillis = System.currentTimeMillis();
  }

  /**
   * Handle local health check from within the VM. If there is no previous normal check or that
   * check has occurred more than checkIntervalSec seconds ago, it returns unhealthy. Otherwise,
   * returns status based value of this.isLastSuccessful, "true" for success and "false" for
   * failure.
   *
   * @param response the HttpServletResponse
   * @throws IOException when it couldn't send out response
   */
  private static void handleLocalHealthCheck(HttpServletResponse response) throws IOException {
    boolean isNormalCheckValid = false;
    if (timeStampOfLastNormalCheckMillis != 0) {
      long timeOffset = System.currentTimeMillis() - timeStampOfLastNormalCheckMillis;
      if (timeOffset <= checkIntervalSec * 1000) {
        isNormalCheckValid = true;
      }
    }
    // If it is a health check, and don't have query parameter for IsLastSuccessful,
    // we will use the internal IsLastSuccessful status as the result. This request
    // is supposed to be from the health check within the virtual machine.
    if (isLastSuccessful && isNormalCheckValid) {
      response.setContentType("text/plain");
      PrintWriter writer = response.getWriter();
      writer.write("ok");
      // Calling flush() on the PrintWriter commits the response.
      writer.flush();
      response.setStatus(HttpServletResponse.SC_OK);
    } else {
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Overrides doScope from ScopedHandler.
   *
   *  Configures a thread local environment before the request is forwarded on to be handled by the
   * SessionHandler, SecurityHandler, and ServletHandler in turn. The environment is required for
   * AppEngine APIs to function. A request specific environment is required since some information
   * is encoded in request headers on the request (for example current user).
   */
  @Override
  public final void doScope(
      String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    String remoteAddr ="dddd";
///        baseRequest.getHttpChannel().getEndPoint().getRemoteAddress().getAddress().getHostAddress();
    if (!isValidRemoteAddr(remoteAddr)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "403 Forbidden");
      return;
    }

    // Handle health check.
    if (isHealthCheck(request)) {
      if (isLocalHealthCheck(request, remoteAddr)) {
        handleLocalHealthCheck(response);
        return;  // Health check is done for local health check.
      } else {
        recordLastNormalHealthCheckStatus(request);
      }
    }

    // Install a thread local environment based on request headers of the current request.
    VmApiProxyEnvironment requestSpecificEnvironment = VmApiProxyEnvironment.createFromHeaders(
        System.getenv(), metadataCache, request, VmRuntimeUtils.getApiServerAddress(),
        null, VmRuntimeUtils.ONE_DAY_IN_MILLIS, defaultEnvironment);

    CommitDelayingResponseServlet3 wrappedResponse = new CommitDelayingResponseServlet3(response);

    if (response instanceof org.eclipse.jetty.server.Response) {
      // The jetty 9.1 HttpOutput class has logic to commit the stream when it reaches a certain
      // threshold.  Inexplicably, by default, that threshold is set to one-fourth its buffer size.
      // That defeats the purpose of our commit delaying response.  Luckily, setting the buffer
      // size again sets the commit size to same value.
      // See go/jetty9-httpoutput.java for the relevant jetty source code.
  //    ((org.eclipse.jetty.server.Response) response).getHttpOutput().setBufferSize(
    //ludo      wrappedResponse.getBufferSize());
    }
    try {
      ApiProxy.setEnvironmentForCurrentThread(requestSpecificEnvironment);
      // Check for SkipAdminCheck and set attributes accordingly.
      VmRuntimeUtils.handleSkipAdminCheck(request);
      // Change scheme to HTTPS based on headers set by the appserver.
      setSchemeAndPort(baseRequest);
      // Forward the request to the rest of the handlers.
      super.doScope(target, baseRequest, request, wrappedResponse);
    } finally {
      try {
        // Interrupt any remaining request threads and wait for them to complete.
        VmRuntimeUtils.interruptRequestThreads(
            requestSpecificEnvironment, VmRuntimeUtils.MAX_REQUEST_THREAD_INTERRUPT_WAIT_TIME_MS);
        // Wait for any pending async API requests to complete.
        if (!VmRuntimeUtils.waitForAsyncApiCalls(requestSpecificEnvironment, wrappedResponse)) {
          logger.warning("Timed out or interrupted while waiting for async API calls to complete.");
        }
        if (!response.isCommitted()) {
          // Flush and set the flush count header so the appserver knows when all logs are in.
          VmRuntimeUtils.flushLogsAndAddHeader(response, requestSpecificEnvironment);
        } else {
          throw new ServletException("Response for request to '" + target
              + "' was already commited (code=" + ((Response) response).getStatus()
              + "). This might result in lost log messages.'");
        }
      } finally {
        try {
          // Complete any pending actions.
          wrappedResponse.commit();
        } finally {
          // Restore the default environment.
          ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
        }
      }
    }
  }
}
