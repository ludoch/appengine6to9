
package com.google.apphosting.runtime;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.AppinfoPb.AppInfo;
import com.google.apphosting.base.ClonePb.CloneSettings;
import com.google.apphosting.base.HttpPb.HttpResponse;
import com.google.apphosting.base.RuntimePb.EvaluationRuntime;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;
import com.google.apphosting.runtime.security.ApplicationEnvironment.RuntimeConfiguration;
import com.google.common.annotations.VisibleForTesting;


import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

/**
 * JavaRuntime implements the Prometheus EvaluationRuntime service.
 * It handles any requests for the "java" runtime.  At the moment,
 * this only includes requests whose handler type is SERVLET.  The
 * handler path is assumed to be the full class name of a class that
 * extends {@link javax.servlet.GenericServlet}.
 *
 * {@code JavaRuntime} is not responsible for configuring {@code
 * ApiProxy} with a delegate.  This class should probably be
 * instantiated by {@code JavaRuntimeFactory}, which also sets up
 * {@code ApiProxy}.
 */
public class JavaRuntime implements EvaluationRuntime.ServerInterface {
  private static final Logger log =
      Logger.getLogger(JavaRuntime.class.getName());

  /**
   * Attempt a graceful shutdown.  If it does not complete within this
   * timeout, then perform an ungraceful shutdown.
   */
  private static final long SHUTDOWN_TIMEOUT_MS = 1000;

  /**
   * ServletEngineAdapter is a wrapper around the servlet engine to
   * whom we are deferring servlet lifecycle and request/response
   * management.
   */
  private final ServletEngineAdapter servletEngine;

  /**
   * A Stubby service will be exposed on this port.  -1 if using UDRPC.
   */
  private final int stubbyPort;

  /**
   * A UDRPC service will be exposed on this transport.  Null if using Stubby.
   */
  private final UdrpcTransport udrpc;

  /**
   * The EventManager used for exporting RPC services.
   */
  private final EventManager eventManager;

  /**
   * {@code AppVersionFactory} can construct {@link AppVersion} instances.
   */
  private final AppVersionFactory appVersionFactory;

  /**
   * Stores a {@link AppVersion} for each application version that has
   * been received by this runtime.
   */
  private final Map<AppVersionKey, AppVersion> appVersionMap;

  /**
   * Handles request setup and tear-down.
   */
  private final RequestManager requestManager;

  /**
   * The string that should be returned by {@code
   * ServletContext.getServerInfo()}.
   */
  private final String runtimeVersion;

  /**
   * If true, verify that the sandbox is attached before processing an
   * AddAppVersion call.
   */
  private final boolean verifySandbox;

  /**
   * A template runtime configuration for applications.
   */
  private final RuntimeConfiguration templateConfiguration;

  /**
   * If true, enable the file system proxy before processing a
   * WaitForSandbox call.
   */
  private final boolean enableFsProxy;

  /**
   * The object responsible for choosing API call deadlines.
   */
  private final ApiDeadlineOracle deadlineOracle;

  private final BackgroundRequestCoordinator coordinator;

  /**
   * This will contain a reference to the Stubby server that we have
   * started after it becomes available.
   */
  private RpcServer rpcServer = null;

  private final boolean compressResponse;

  public JavaRuntime(ServletEngineAdapter servletEngine,
                     int stubbyPort,
                     UdrpcTransport udrpc,
                     EventManager eventManager,
                     File sharedDirectory,
                     File builtinDirectory,
                     RequestManager requestManager,
                     String runtimeVersion,
                     boolean verifySandbox,
                     Set<String> securityTrustedAppIds,
                     RuntimeConfiguration configuration,
                     boolean enableFsProxy,
                     ApiDeadlineOracle deadlineOracle,
                     BackgroundRequestCoordinator coordinator,
                     boolean compressResponse) {

    this.servletEngine = servletEngine;
    this.stubbyPort = stubbyPort;
    this.udrpc = udrpc;
    this.eventManager = eventManager;
    this.requestManager = requestManager;
    this.appVersionFactory = new AppVersionFactory(
        sharedDirectory, builtinDirectory, securityTrustedAppIds, runtimeVersion);
    this.appVersionMap = Maps.newHashMap();
    this.runtimeVersion = runtimeVersion;
    this.verifySandbox = verifySandbox;
    this.templateConfiguration = configuration;
    this.enableFsProxy = enableFsProxy;
    this.deadlineOracle = deadlineOracle;
    this.coordinator = coordinator;
    this.compressResponse = compressResponse;
  }

  /**
   * Fire up our Stubby service, and then perform any initialization that
   * the servlet engine requires.
   */
  public void start() {
    log.info("JavaRuntime starting...");

    Exchanger<Object> exchanger = new Exchanger<Object>();

    new Thread(new RpcRunnable(exchanger), "Runtime Network Thread").start();

    // Wait for the servlet engine to start up.
    servletEngine.start("Google App Engine/" + runtimeVersion);

    // Wait for our Stubby service to start up.
    Object response;
    try {
      response = exchanger.exchange(null);
    } catch (InterruptedException ex) {
      throw new RuntimeException("Interrupted while starting runtime", ex);
    }
    if (response instanceof Error) {
      throw (Error) response;
    } else if (response instanceof RuntimeException) {
      throw (RuntimeException) response;
    } else if (response instanceof Throwable) {
      throw new RuntimeException(((Throwable) response));
    } else if (response instanceof RpcServer) {
      // Success.  Save the server for later.
      rpcServer = (RpcServer) response;
    } else {
      throw new RuntimeException("Unknown response: " + response);
    }
  }

  /**
   * Perform a graceful shutdown of our Stubby service, and then shut down
   * our servlet engine.
   */
  public void stop() {
    log.info("JavaRuntime stopping...");

    if (rpcServer != null) {
      try {
        rpcServer.shutdownGracefully(SHUTDOWN_TIMEOUT_MS, true);
      } catch (Exception ex) {
        // Ignore.
      }
      log.info("JavaRuntime stopped.");
    } else {
      log.info("Asked to stop, but we have no RpcServer registered.");
    }

    servletEngine.stop();
  }

  /**
   * Translate the specified UPRequest from Prometheus into a {@link
   * javax.servlet.http.HttpServletRequest}, invoke the specified
   * servlet, and translate the response back into an UPResponse.
   */
///  @Override
  public void handleRequest(RpcServerContext rpc, UPRequest upRequest) {
    UPResponse upResponse = new UPResponse();
    AppVersionKey appVersionKey = AppVersionKey.fromUpRequest(upRequest);
    log.fine("Received handleRequest for " + appVersionKey);

    AppVersion appVersion = appVersionMap.get(appVersionKey);
    if (appVersion == null) {
/////
      rpc.finishWithResponse(upResponse);
      return;
    }

    try {
      appVersion.getThreadGroupPool().start(
          "Request " + upRequest.getEventIdHash(),
          TracePropagation.propagating(
              new RequestRunnable(appVersion, rpc, upRequest, upResponse)));
    } catch (Exception ex) {
//////////////////////      setFailure(upResponse, UPResponse.ERROR.APP_FAILURE,
//////////////////////                 "Interrupted while starting request thread: " + ex);
      rpc.finishWithResponse(upResponse);

    }
  }

  /**
   * Adds the specified application version so that it can be used for
   * future requests.
   */
  public synchronized void addAppVersion(RpcServerContext rpc, AppInfo appInfo) {

    try {
      // Record everything we now know about the app for future requests.

      AppVersion appVersion = appVersionFactory.createAppVersion(appInfo,
          new RuntimeConfiguration(templateConfiguration));
      appVersionMap.put(appVersion.getKey(), appVersion);
      // Now notify the servlet engine, so it can do any setup it
      // has to do.
      servletEngine.addAppVersion(appVersion);
    } catch (Exception ex) {
      log.log(Level.WARNING, "Error adding app version:", ex);
      return;
    }
    // Do not put this in a finally block.  If we propagate an
    // exception the callback will be invoked automatically.
    rpc.finishWithResponse(new EmptyMessage());
  }

  /**
   * Deletes the specified application, so that future requests will fail.
   */
 //// @Override
  public synchronized void deleteAppVersion(RpcServerContext rpc, AppInfo appInfo) {
    AppVersionKey appVersionKey = AppVersionKey.fromAppInfo(appInfo);

    AppVersion appVersion = appVersionMap.get(appVersionKey);
    if (appVersion != null) {
      // We knew about this app version -- remove it.
      appVersionMap.remove(appVersionKey);
      // Also notify the servlet engine, so it can do its own cleanup.
      servletEngine.deleteAppVersion(appVersion);
    }
    rpc.finishWithResponse(new EmptyMessage());
  }



  @VisibleForTesting
  void setCloneSettings(CloneSettings settings) {
    appVersionFactory.setCloneSettings(settings);
  }



  private String formatLogLine(String message, Throwable ex) {
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    printWriter.println(message);
    ex.printStackTrace(printWriter);
    return stringWriter.toString();
  }

  private boolean shouldKillCloneAfterException(Throwable th) {
    while (th != null) {
      if (th instanceof OutOfMemoryError) {
        return true;
      }
      // TODO(schwardo): Consider checking for other subclasses of
      // VirtualMachineError, but probably not StackOverflowError.
      th = th.getCause();
    }
    return false;
  }

  private String getBackgroundRequestId(UPRequest upRequest) {
//////
    throw new IllegalArgumentException("Did not receive a background request identifier.");
  }

  public class RequestRunnable implements Runnable {
    private final AppVersion appVersion;
    private final RpcServerContext rpc;
    private final UPRequest upRequest;
    private final UPResponse upResponse;

    private RequestRunnable(AppVersion appVersion, RpcServerContext rpc,
                            UPRequest upRequest, UPResponse upResponse) {
      this.appVersion = appVersion;
      this.rpc = rpc;
      this.upRequest = upRequest;
      this.upResponse = upResponse;
    }

    @Override
    public void run() {
      ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
      RequestManager.RequestToken requestToken = requestManager.startRequest(
          appVersion, rpc, upRequest, upResponse, currentThreadGroup);
      boolean backgroundRequest = false;
      try {
        try {
          if (upRequest.getRequestType() == UPRequest.SHUTDOWN) {
            log.info("Shutting down requests");
            requestManager.shutdownRequests(requestToken);
          } else if (upRequest.getRequestType() == UPRequest.BACKGROUND) {
            backgroundRequest = true;
            String requestId = getBackgroundRequestId(upRequest);
            // Wait here for synchronization with the ThreadFactory.
            CountDownLatch latch = ThreadGroupPool.resetCurrentThread();
            Runnable runnable = coordinator.waitForUserRunnable(requestId, Thread.currentThread());
            // Wait here until someone calls start() on the thread again.
            latch.await();
            // Now set the context class loader to the UserClassLoader for the application
            // and pass control to the Runnable the user provided.
            ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(appVersion.getClassLoader());
            try {
              runnable.run();
            } finally {
              Thread.currentThread().setContextClassLoader(oldClassLoader);
            }
 ///           upResponse.setError(UPResponse.ERROR.OK.getValue());
            if (!upResponse.hasHttpResponse()) {
              // If the servlet handler did not write an HTTPResponse
              // already, provide a default one.  This ensures that
              // the code receiving this response does not mistake the
              // lack of an HTTPResponse field for an internal server
              // error (500).
              HttpResponse httpResponse = upResponse.getMutableHttpResponse();
              httpResponse.setResponsecode(200);
              httpResponse.setResponse("OK");
            }
          } else {
            servletEngine.serviceRequest(upRequest, upResponse);

          }
        } finally {
          requestManager.finishRequest(requestToken);
        }
      } catch (Throwable ex) {
        // Unwrap ServletExceptions
        if (ex instanceof ServletException) {
          ServletException sex = (ServletException) ex;
          if (sex.getRootCause() != null) {
            ex = sex.getRootCause();
          }
        }
        String msg = "Uncaught exception from servlet";
        log.log(Level.WARNING, msg, ex);
        // Don't use ApiProxy here, because we don't know what state the
        // environment/delegate are in.
        requestToken.addAppLogMessage(ApiProxy.LogRecord.Level.fatal, formatLogLine(msg, ex));

        if (shouldKillCloneAfterException(ex)) {
          log.log(Level.SEVERE, "Detected a dangerous exception, shutting down clone nicely.");
          upResponse.setTerminateClone(true);
        }

   //     setFailure(upResponse, error, "Unexpected exception from servlet: " + ex);
      }
      // Do not put this in a finally block.  If we propagate an
      // exception the callback will be invoked automatically.
      rpc.finishWithResponse(upResponse);
      // We don't want threads used for background requests to go back
      // in the thread pool, because users may have stashed references
      // to them or may be expecting them to exit.  Setting the
      // interrupt bit causes the pool to drop them.
      if (backgroundRequest) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private class RpcRunnable implements Runnable {
    private final Exchanger<Object> exchanger;

    public RpcRunnable(Exchanger<Object> exchanger) {
      this.exchanger = exchanger;
    }

    @Override
    public void run() {
      try {
        // NOTE: This method never returns -- this thread is now the
        // network thread and will be responsible for accepting socket
        // connections in a loop and handing off control to the
        // Executor created above.
        startServer();
      } catch (Throwable ex) {
        log.log(Level.SEVERE, "JavaRuntime server could not start", ex);
        try {
          // Something went wrong.  Pass the exception back.
          exchanger.exchange(ex);
        } catch (InterruptedException ex2) {
          throw new RuntimeException(ex2);
        }
      }
    }

    private void startServer() throws Exception {
      boolean usingUdrpc = (udrpc != null);
      if (usingUdrpc) {
        log.info("Starting to listen over UDRPC");
      } else {
        log.info("Starting to listen on port " + stubbyPort);
      }
      CloneControllerImpl controller = new CloneControllerImpl(
              eventManager,
              enableFsProxy,
              deadlineOracle,
              requestManager,
              appVersionFactory);
      RpcServer server;
//////      if (usingUdrpc) {
//////        server = udrpc.createAndStartServer(
//////            EvaluationRuntime.newService(JavaRuntime.this),
//////            CloneController.newService(controller));
//////      } else {
        RpcServer.Builder serverBuilder = RpcServer.newBuilder(stubbyPort);
        serverBuilder.setEventManager(eventManager);
 //       serverBuilder.addService(EvaluationRuntime.newService(JavaRuntime.this));
 //       serverBuilder.addService(CloneController.newService(controller));
        server = serverBuilder.createAndStart();
    ////}
      controller.setRpcServer(server);

      log.info("Now listening on port " + stubbyPort);

      // Our RpcServer instance is now initialized.  Pass it back.
      exchanger.exchange(server);

      try {
        log.info("Beginning accept loop.");
        // This must run in the same thread that created the EventDispatcher.
        server.blockUntilShutdown();
      } catch (Throwable ex) {

        ex.printStackTrace();

      }
    }
  }
}
