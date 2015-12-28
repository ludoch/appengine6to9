
package com.google.apphosting.runtime;

import com.google.apphosting.api.ApiProxy.LogRecord;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiStats;
import com.google.apphosting.base.HttpPb.ParsedHttpHeader;
import com.google.apphosting.base.RuntimePb.APIHost;
import com.google.apphosting.base.RuntimePb.APIRequest;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.MoreExecutors;
//////////////////////

import com.google.appengine.tools.development.TimedFuture;
import com.google.apphosting.api.ApiProxy.Environment;

import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ApiProxyImpl
    implements ApiProxy.Delegate<ApiProxyImpl.EnvironmentImpl>, EnvironmentFactory {

  private static final Logger log =
      Logger.getLogger(ApiProxyImpl.class.getName());

 
  /**
   * The number of milliseconds beyond the API call deadline to wait
   * to receive a Stubby callback before throwing a
   * {@link ApiProxy.ApiDeadlineExceededException} anyway.
   */
  private static final int API_DEADLINE_PADDING = 500;



  private final APIHost.ClientInterface apiHost;
  private final ApiDeadlineOracle deadlineOracle;
  private final String externalDatacenterName;
  private final long defaultByteCountBeforeFlushing;
  private final int maxLogFlushSeconds;
  private final BackgroundRequestCoordinator coordinator;
  private RequestManager requestManager;
  private final boolean cloudSqlJdbcConnectivityEnabled;

  @VisibleForTesting
  public ApiProxyImpl(APIHost.ClientInterface apiHost, ApiDeadlineOracle deadlineOracle,
      String externalDatacenterName,
      long byteCountBeforeFlushing, int maxLogFlushSeconds,
      BackgroundRequestCoordinator coordinator) {
    // The value of the cloudSqlJdbcConnectivityEnabled parameter indicates
    // if the CloudSQL JDBC connectivity feature is enabled(true) or not.
    this(apiHost, deadlineOracle, externalDatacenterName,
        byteCountBeforeFlushing, maxLogFlushSeconds, coordinator, false);
  }

  public ApiProxyImpl(APIHost.ClientInterface apiHost, ApiDeadlineOracle deadlineOracle,
                      String externalDatacenterName,
                      long byteCountBeforeFlushing, int maxLogFlushSeconds,
                      BackgroundRequestCoordinator coordinator,
                      boolean cloudSqlJdbcConnectivityEnabled) {
    this.apiHost = apiHost;
    this.deadlineOracle = deadlineOracle;
    this.externalDatacenterName = externalDatacenterName;
    this.defaultByteCountBeforeFlushing = byteCountBeforeFlushing;
    this.maxLogFlushSeconds = maxLogFlushSeconds;
    this.coordinator = coordinator;
    this.cloudSqlJdbcConnectivityEnabled = cloudSqlJdbcConnectivityEnabled;
  }

  // TODO(schwardo) There's a circular dependency here:
  // RequestManager needs the EnvironmentFactory so it can create
  // environments, and ApiProxyImpl needs the RequestManager so it can
  // get the request threads. We should find a better way to
  // modularize this.
  public void setRequestManager(RequestManager requestManager) {
    this.requestManager = requestManager;
  }

  @Override
  public byte[] makeSyncCall(final EnvironmentImpl environment,
                             final String packageName,
                             final String methodName,
                             final byte[] request)
      throws ApiProxy.ApiProxyException {
    return AccessController.doPrivileged(new PrivilegedAction<byte[]>() {
      @Override
      public byte[] run() {
        return doSyncCall(environment, packageName, methodName, request);
      }
    });
  }

  @Override
  public Future<byte[]> makeAsyncCall(final EnvironmentImpl environment,
                                      final String packageName,
                                      final String methodName,
                                      final byte[] request,
                                      final ApiProxy.ApiConfig apiConfig) {
    return AccessController.doPrivileged(new PrivilegedAction<Future<byte[]>>() {
      @Override
      public Future<byte[]> run() {
        return doAsyncCall(environment, packageName, methodName, request,
                           apiConfig.getDeadlineInSeconds());
      }
    });
  }

  private byte[] doSyncCall(EnvironmentImpl environment,
                            String packageName,
                            String methodName,
                            byte[] requestBytes)
      throws ApiProxy.ApiProxyException {
    double deadlineInSeconds = getApiDeadline(packageName, environment);
    Future<byte[]> future = doAsyncCall(environment, packageName, methodName,
                                        requestBytes, deadlineInSeconds);
    try {
      return future.get((long) (deadlineInSeconds * 1000), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      // Someone else called Thread.interrupt().  We probably
      // shouldn't swallow this, so propagate it as the closest
      // exception that we have.  Note that we specifically do not
      // re-set the interrupt bit because we don't want future API
      // calls to immediately throw this exception.
      log.log(Level.WARNING,
              "Thread was interrupted, throwing CancelledException.", ex);
      throw new ApiProxy.CancelledException(packageName, methodName);
    } catch (CancellationException ex) {
      log.log(Level.SEVERE, "Synchronous call was cancelled.  Should not happen.", ex);
      throw new ApiProxy.CancelledException(packageName, methodName);
    } catch (TimeoutException ex) {
      log.log(Level.INFO, "API call exceeded deadline", ex);
      throw new ApiProxy.ApiDeadlineExceededException(packageName, methodName);
    } catch (ExecutionException ex) {
      // This will almost always be an ApiProxyException.
      Throwable cause = ex.getCause();
      if (cause instanceof ApiProxy.ApiProxyException) {
        // The ApiProxyException was generated during a callback in a Stubby
        // thread, so the stack trace it contains is not very useful to the user.
        // It would be more useful to the user to replace the stack trace with
        // the current stack trace. But that might lose some information that
        // could be useful to an App Engine developer. So we throw a copy of the
        // original exception that contains the current stack trace and contains
        // the original exception as the cause.
        ApiProxy.ApiProxyException apiProxyException = (ApiProxy.ApiProxyException) cause;
        throw apiProxyException.copy(Thread.currentThread().getStackTrace());
      } else if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        log.log(Level.SEVERE, "Error thrown from API call.", cause);
        throw (Error) cause;
      } else {
        // Shouldn't happen, but just in case.
        log.log(Level.WARNING, "Checked exception thrown from API call.", cause);
        throw new RuntimeException(cause);
      }
    } finally {
      // We used to use CountDownLatch for this wait, which could end
      // up setting the interrupt bit for this thread even if no
      // InterruptedException was thrown.  This should no longer be
      // the case, but we've leaving this code here temporarily.
      if (Thread.interrupted()) {
        log.warning(
            "Thread " + Thread.currentThread() + " was interrupted but we " +
            "did not receive an InterruptedException.  Resetting interrupt bit.");
        // Calling interrupted() already reset the bit.
      }
    }
  }

  private Future<byte[]> doAsyncCall(final EnvironmentImpl environment,
                                     String packageName,
                                     final String methodName,
                                     byte[] requestBytes,
                                     Double requestDeadlineInSeconds) {
    // Browserchannel messages are actually sent via XMPP, so this cheap hack
    // translates the packageName in production.  If these two services are
    // ever separated, this should be removed.
    if (packageName.equals("channel")) {
      packageName = "xmpp";
    }

    double deadlineInSeconds = deadlineOracle.getDeadline(packageName,
                                                          environment.isOfflineRequest(),
                                                          requestDeadlineInSeconds);

    APIRequest apiRequest = new APIRequest();
    apiRequest.setApiPackage(packageName);
    apiRequest.setCall(methodName);
    apiRequest.setSecurityTicket(environment.getSecurityTicket());
    apiRequest.setPbAsBytes(requestBytes);

    RpcClientContext rpc = RpcClientContext.create();
    try {
      environment.apiRpcStarting();
    } catch (InterruptedException ex) {
      log.log(Level.WARNING, "Interrupted waiting for an API RPC slot:", ex);
      return createCancelledFuture(packageName, methodName);
    }

    rpc.setDeadline(deadlineInSeconds);

  
    final String packageNameFinal = packageName;
    SettableFuture<byte[]> settableFuture = SettableFuture.create();
    long deadlineMillis = (long) (deadlineInSeconds * 1000.0);
    Future<byte[]> timedFuture = new TimedFuture<byte[]>(settableFuture,
                                                         deadlineMillis + API_DEADLINE_PADDING) {
      @Override
      protected RuntimeException createDeadlineException() {
        throw new ApiProxy.ApiDeadlineExceededException(packageNameFinal, methodName);
      }
    };
//////    AsyncApiFuture rpcCallback = new AsyncApiFuture(timedFuture, settableFuture,
//////                                                    rpc, environment,
//////                                                    packageName, methodName);
//////    apiHost.call(rpc, apiRequest, rpcCallback);

    settableFuture.addListener(new Runnable() {
        @Override
        public void run() {
          environment.apiRpcFinished();
        }
    }, MoreExecutors.sameThreadExecutor());

///ludo commented    environment.addAsyncFuture(rpcCallback);
    return null;// rpcCallback;
  }



  private Future<byte[]> createCancelledFuture(final String packageName, final String methodName) {
    return new Future<byte[]>() {
      @Override
      public byte[] get() {
        throw new ApiProxy.CancelledException(packageName, methodName);
      }

      @Override
      public byte[] get(long deadline, TimeUnit unit) {
        throw new ApiProxy.CancelledException(packageName, methodName);
      }

      @Override
      public boolean isDone() {
        return true;
      }

      @Override
      public boolean isCancelled() {
        return true;
      }

      @Override
      public boolean cancel(boolean shouldInterrupt) {
        return false;
      }
    };
  }


  @Override
  public void log(EnvironmentImpl environment, LogRecord record) {
    if (environment != null) {
      environment.addLogRecord(record);
    }
  }

  @Override
  public void flushLogs(EnvironmentImpl environment) {
    if (environment != null) {
      environment.flushLogs();
    }
  }

  @Override
  public List<Thread> getRequestThreads(EnvironmentImpl environment) {
    return requestManager.getRequestThreads(environment.getAppVersion().getKey());
  }

  /**
   * Creates an {@link Environment} instance that is suitable for use
   * with this class.
   */
  @Override
  public EnvironmentImpl createEnvironment(AppVersion appVersion, UPRequest upRequest,
                                           UPResponse upResponse, CpuRatioTimer requestTimer,
                                           String requestId,
                                           List<Future<?>> asyncFutures,
                                           Semaphore outstandingApiRpcSemaphore,
                                           ThreadGroup requestThreadGroup,
                                           RequestState requestState,
                                           /* @Nullable */
                                           Long millisUntilSoftDeadline) {
    return new EnvironmentImpl(appVersion, upRequest, upResponse, requestTimer, requestId,
                               externalDatacenterName,
                               asyncFutures,
                               outstandingApiRpcSemaphore,
                               defaultByteCountBeforeFlushing,
                               maxLogFlushSeconds,
                               requestThreadGroup,
                               requestState,
                               coordinator,
                               cloudSqlJdbcConnectivityEnabled,
                               millisUntilSoftDeadline);
  }

  /**
   * Determine the API deadline to use for the specified Environment.
   * The default deadline for that package is used, unless an entry is
   * present in {@link Environment#getAttributes} with a key of {@code
   * API_DEADLINE_KEY} and a value of a {@link Number} object.  In
   * either case, the deadline cannot be higher than maximum deadline
   * for that package.
   */
  private double getApiDeadline(String packageName, EnvironmentImpl env) {
     return deadlineOracle.getDeadline(packageName, env.isOfflineRequest(), 9999);
  }

  private static final class ApiStatsImpl extends ApiStats {

    /**
     * Time spent in api cycles. This is basically an aggregate of all calls to
     * apiResponse.getCpuUsage().
     */
    private long apiTime;

    private final EnvironmentImpl env;

    ApiStatsImpl(EnvironmentImpl env) {
      super(env);
      this.env = env;
    }

    @Override
    public long getApiTimeInMegaCycles() {
      return apiTime;
    }

    @Override
    public long getCpuTimeInMegaCycles() {
      return env.getRequestTimer().getCycleCount() / 1000000;
    }

    /**
     * Set the overall time spent in API cycles, as returned by the system.
     * @param delta a delta to increase the value by (in megacycles of CPU time)
     */
    private void increaseApiTimeInMegacycles(long delta) {
      this.apiTime += delta;
    }
  }

  /**
   * To implement ApiProxy.Environment, we use a class that wraps
   * around an UPRequest and retrieves the necessary information from
   * it.
   */
  public static final class EnvironmentImpl implements ApiProxy.Environment {

    // While the cloneSettings should always have this set, we want a default
    // just in case.
    @VisibleForTesting
    static final int DEFAULT_MAX_LOG_LINE_SIZE = 8 * 1024;

    private final AppVersion appVersion;
    private final UPRequest upRequest;
    private final CpuRatioTimer requestTimer;
    private final Map<String, Object> attributes;
    private final String requestId;
    private final List<Future<?>> asyncFutures;
    private final boolean isFederatedLoginUser;
   ///// private final AppLogsWriter appLogsWriter;
    private final Semaphore outstandingApiRpcSemaphore;
    private final ThreadGroup requestThreadGroup;
    private final RequestState requestState;
    /* @Nullable */
    private final Long millisUntilSoftDeadline;
    private Object cloneSettings;

    EnvironmentImpl(AppVersion appVersion,
                    UPRequest upRequest,
                    UPResponse upResponse,
                    CpuRatioTimer requestTimer,
                    String requestId,
                    String externalDatacenterName,
                    List<Future<?>> asyncFutures,
                    Semaphore outstandingApiRpcSemaphore,
                    long defaultByteCountBeforeFlushing,
                    int maxLogFlushSeconds,
                    ThreadGroup requestThreadGroup,
                    RequestState requestState,
                    BackgroundRequestCoordinator coordinator,
                    boolean cloudSqlJdbcConnectivityEnabled,
                    /* @Nullable */
                    Long millisUntilSoftDeadline) {
      this.appVersion = appVersion;
      this.upRequest = upRequest;
      this.requestTimer = requestTimer;
      this.requestId = requestId;
      this.isFederatedLoginUser = upRequest.hasFederatedIdentity();
      this.asyncFutures = asyncFutures;
      Iterator<ParsedHttpHeader> headers =
        null;//////////  upRequest.getRequest().headersIterator();
      this.attributes = createInitialAttributes(
          upRequest, externalDatacenterName, coordinator, cloudSqlJdbcConnectivityEnabled);
      this.outstandingApiRpcSemaphore = outstandingApiRpcSemaphore;
      this.requestState = requestState;
      this.millisUntilSoftDeadline = millisUntilSoftDeadline;

      while (headers.hasNext()) {
        ParsedHttpHeader header = headers.next();
//....
      }

//....


      this.requestThreadGroup = requestThreadGroup;
    }

    /**
     * May block if there are already too many API calls in progress.
     */
    void apiRpcStarting() throws InterruptedException {
      outstandingApiRpcSemaphore.acquire();
    }

    void apiRpcFinished() {
      outstandingApiRpcSemaphore.release();
    }

    void addAsyncFuture(Future<?> future) {
      asyncFutures.add(future);
    }

    boolean removeAsyncFuture(Future<?> future) {
      return asyncFutures.remove(future);
    }

    private static Map<String, Object> createInitialAttributes(
        UPRequest upRequest,
        String externalDatacenterName,
        BackgroundRequestCoordinator coordinator,
        boolean cloudSqlJdbcConnectivityEnabled) {
      Map<String, Object> attributes = new HashMap<String, Object>();
      


      // Environments are associated with requests, and can now be
      // shared across more than one thread.  We'll synchronize all
      // individual calls, which should be sufficient.
      return Collections.synchronizedMap(attributes);
    }

    public void addLogRecord(LogRecord record) {
    ///  appLogsWriter.addLogRecordAndMaybeFlush(record);
    }

    public void flushLogs() {
   ////   appLogsWriter.flushAndWait();
    }

    @Override
    public String getAppId() {
      return upRequest.getAppId();
    }

    @Override
    public String getModuleId() {
      return upRequest.getModuleId();
    }

    @Override
    public String getVersionId() {
      // We use the module_version_id field because the version_id field has the 'module:version'
      // form.
      return upRequest.getModuleVersionId();
    }

    public AppVersion getAppVersion() {
      return appVersion;
    }

    @Override
    public boolean isLoggedIn() {
      // TODO(schwardo): It would be nice if UPRequest had a bool for this.
      return isFederatedLoginUser || upRequest.getEmail().length() > 0;
    }

    @Override
    public boolean isAdmin() {
      return upRequest.isIsAdmin();
    }

    @Override
    public String getEmail() {
      return upRequest.getEmail();
    }

    @Override
    public String getAuthDomain() {
      return upRequest.getAuthDomain();
    }

    @Override
    @Deprecated
    public String getRequestNamespace() {
      return "" ;
    }

    @Override
    public Map<String, Object> getAttributes() {
      return attributes;
    }

    /**
     * Returns the security ticket associated with this environment.
     *
     * Note that this method is not available on the public
     * Environment interface, as it is used internally by ApiProxyImpl
     * and there is no reason to expose it to applications.
     */
    String getSecurityTicket() {
      return upRequest.getSecurityTicket();
    }

//    boolean isOfflineRequest() {
//      return upRequest.getRequest().isIsOffline();
//    }

    /**
     * Returns the request id associated with this environment.
     *
     * Note that this method is not available on the public
     * Environment interface, as it is used reflectively by
     * {@link com.google.apphosting.runtime.security.UserClassLoader}
     * and there is no reason to expose it to applications.
     */
    String getRequestId() {
      return requestId;
    }

    CpuRatioTimer getRequestTimer() {
      return requestTimer;
    }

    ThreadGroup getRequestThreadGroup() {
      return requestThreadGroup;
    }

    RequestState getRequestState() {
      return requestState;
    }

    @Override
    public long getRemainingMillis() {
      if (millisUntilSoftDeadline == null) {
        return Long.MAX_VALUE;
      }
      return 1000l;
    }

    /**
     * Get the {@link AppLogsWriter} instance that is used by the
     * {@link #addLogRecord(LogRecord)} and {@link #flushLogs()} methods.
     *
     * This method is not simply visible for testing, it only exists for testing.
     */
    @VisibleForTesting
    AppLogsWriter getAppLogsWriter() {
      return null;
    }

    private boolean isOfflineRequest() {
      throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
  }

  private final static class CurrentRequestThreadFactory implements ThreadFactory {
    private static ThreadFactory SINGLETON = new CurrentRequestThreadFactory();

    @Override
    public Thread newThread(final Runnable runnable) {
      final EnvironmentImpl environment = (EnvironmentImpl) ApiProxy.getCurrentEnvironment();
      final ThreadGroup requestThreadGroup = environment.getRequestThreadGroup();
      final CpuRatioTimer requestTimer = environment.getRequestTimer();
      final RequestState requestState = environment.getRequestState();

      // TODO(schwardo): Remove this for general availability of request threads.
      if (!environment.getAppVersion().getEnvironment().userThreadsEnabled()) {
        throw new ApiProxy.FeatureNotEnabledException(
            "Request threads are not yet available to this application.");
      }

      final AccessControlContext context = AccessController.getContext();
      final Runnable contextRunnable = new Runnable() {
          @Override
          public void run() {
            AccessController.doPrivileged(
                new PrivilegedAction<Object>() {
                  @Override
                  public Object run() {
                    runnable.run();
                    return null;
                  }
                }, context);
          }
      };
      return AccessController.doPrivileged(
        new PrivilegedAction<Thread>() {
          @Override
          public Thread run() {
            return new Thread(requestThreadGroup, contextRunnable) {
              @Override
              public void start() {
                if (!requestState.getAllowNewRequestThreadCreation()) {
                  throw new IllegalStateException(
                      "Cannot create new threads after request thread stops.");
                }
                int existingThreads = AccessController.doPrivileged(
                    new PrivilegedAction<Integer>() {
                      @Override
                      public Integer run() {
                        return requestThreadGroup.enumerate(
                            new Thread[ThreadGroupPool.MAX_THREADS_PER_THREAD_GROUP + 1],
                            true);
                      }
                });
                if (existingThreads > ThreadGroupPool.MAX_THREADS_PER_THREAD_GROUP) {
                  throw new IllegalStateException(
                      "Each request cannot exceed " + ThreadGroupPool.MAX_THREADS_PER_THREAD_GROUP +
                      " active threads.");
                }
                super.start();
              }

              @Override
              public void run() {
                ApiProxy.setEnvironmentForCurrentThread(environment);
                super.run();
              }
            };
          }
      });
    }
  }


}
