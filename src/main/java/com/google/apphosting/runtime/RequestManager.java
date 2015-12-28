
package com.google.apphosting.runtime;


import com.google.appengine.api.LifecycleManager;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.DeadlineExceededException;
import com.google.apphosting.base.AppLogsPb.AppLogLine;
import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.HttpPb.HttpResponse;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;
////

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code RequestManager} is responsible for setting up and tearing
 * down any state associated with each request.
 *
 * At the moment, this includes:
 * <ul>
 *  <li>Injecting an {@code Environment} implementation for the
 *  request's thread into {@code ApiProxy}.
 *  <li>Scheduling any future actions that must occur while the
 *  request is executing (e.g. deadline exceptions), and cleaning up
 *  any scheduled actions that do not occur.
 * </ul>
 *
 * It is expected that clients will use it like this:
 * <pre>
 * RequestManager.RequestToken token =
 *     requestManager.startRequest(...);
 * try {
 *   ...
 * } finally {
 *   requestManager.finishRequest(token);
 * }
 * </pre>
 *
 */
public class RequestManager {
  private static final Logger log =
      Logger.getLogger(RequestManager.class.getName());

  /**
   * The number of threads to use to execute scheduled {@code Future}
   * actions.
   */
  private static final int SCHEDULER_THREADS = 1;

  // SimpleDateFormat is not threadsafe, so we'll just share the format string and let
  // clients instantiate the format instances as-needed.  At the moment the usage of the format
  // objects shouldn't be too high volume, but if the construction of the format instance ever has
  // a noticeable impact on performance (unlikely) we can switch to one format instance per thread
  // using a ThreadLocal.
  private static final String SIMPLE_DATE_FORMAT_STRING = "yyyy/MM/dd HH:mm:ss.SSS z";

  /**
   * The maximum number of stack frames to log for each thread when
   * logging a deadlock.
   */
  private static final int MAXIMUM_DEADLOCK_STACK_LENGTH = 20;

  /**
   * Maximum number of threads that can be associated with a given request.
   */
  // TODO(schwardo): Guarantee that users can't create more than this number of threads.
  private static final int MAX_THREADS = 128;

  private static final ThreadMXBean THREAD_MX =
      ManagementFactory.getThreadMXBean();

  private final long softDeadlineDelay;
  private final long hardDeadlineDelay;
  private final boolean disableDeadlineTimers;
  private final ScheduledThreadPoolExecutor executor;
////  private final TimerFactory timerFactory;
  private final RuntimeLogSink runtimeLogSink;
  private final EnvironmentFactory environmentFactory;
  private final boolean threadStopTerminatesClone;
  private final Map<String, RequestToken> requests;
/////  private final ProfilerFactory profilerFactory;
  private int maxOutstandingApiRpcs;
  private long defaultMinContentSizeBuffer = Long.MAX_VALUE;

  public RequestManager(long softDeadlineDelay, long hardDeadlineDelay,
                        boolean disableDeadlineTimers,
                        RuntimeLogSink runtimeLogSink,
                        EnvironmentFactory environmentFactory, int maxOutstandingApiRpcs,
                        boolean threadStopTerminatesClone) {
    this.softDeadlineDelay = softDeadlineDelay;
    this.hardDeadlineDelay = hardDeadlineDelay;
    this.disableDeadlineTimers = disableDeadlineTimers;
    this.executor = new ScheduledThreadPoolExecutor(SCHEDULER_THREADS);
////    this.timerFactory = null;
    this.runtimeLogSink = runtimeLogSink;
    this.environmentFactory = environmentFactory;
    this.maxOutstandingApiRpcs = maxOutstandingApiRpcs;
    this.threadStopTerminatesClone = threadStopTerminatesClone;
    this.requests = Collections.synchronizedMap(new HashMap<String, RequestToken>());
 /////   this.profilerFactory = null;
  }

  public void setMaxOutstandingApiRpcs(int maxOutstandingApiRpcs) {
    this.maxOutstandingApiRpcs = maxOutstandingApiRpcs;
  }

  public void setDefaultMinContentSizeBuffer(long defaultMinContentSizeBuffer) {
    this.defaultMinContentSizeBuffer = defaultMinContentSizeBuffer;
  }

  /**
   * Set up any state necessary to execute a new request using the
   * specified parameters.  The current thread should be the one that
   * will execute the new request.
   *
   * @return a {@code RequestToken} that should be passed into {@code
   * finishRequest} after the request completes.
   */
  public RequestToken startRequest(AppVersion appVersion,
                                   RpcServerContext rpc,
                                   UPRequest upRequest,
                                   UPResponse upResponse,
                                   ThreadGroup requestThreadGroup) {
    long remainingTime = getAdjustedRpcDeadline(rpc, 60000);
    long softDeadlineMillis = Math.max(getAdjustedRpcDeadline(rpc, -1) - softDeadlineDelay, -1);
    long millisUntilSoftDeadline = remainingTime - softDeadlineDelay;
    Thread thread = Thread.currentThread();

    // Hex-encode the request-id, formatted to 16 digits, in lower-case,
    // with leading 0s, and no leading 0x to match the way stubby
    // request ids are formatted in Google logs.
    String requestId = String.format("%1$016x", rpc.getGlobalId());
    log.info("Beginning request " + requestId + " remaining millis : " + remainingTime);

 ////   Profiler profiler = profilerFactory.getProfilerForRequest(upRequest);
 /////   profiler.start();

////    CpuRatioTimer timer = timerFactory.getCpuRatioTimer(thread);

    // This list is used to block the end of a request until all API
    // calls have completed or timed out.
    List<Future<?>> asyncFutures = Collections.synchronizedList(new ArrayList<Future<?>>());
    // This semaphore maintains the count of currently running API
    // calls so we can block future calls if too many calls are
    // outstanding.
    Semaphore outstandingApiRpcSemaphore = new Semaphore(maxOutstandingApiRpcs);

    RequestState state = new RequestState();

    ApiProxy.Environment environment = environmentFactory.createEnvironment(
        appVersion, upRequest, upResponse, null, requestId, asyncFutures,
        outstandingApiRpcSemaphore, requestThreadGroup, state,
        millisUntilSoftDeadline);
    // Create a RequestToken where we will store any state we will
    // need to restore in finishRequest().
    RequestToken token = new RequestToken(thread, upResponse, requestId,
                                          upRequest.getSecurityTicket(), null,
                                          asyncFutures, appVersion,
                                          softDeadlineMillis, rpc,
                                          121212, 
                                          requestThreadGroup, state);

    requests.put(upRequest.getSecurityTicket(), token);

    // Tell the ApiProxy about our current request environment so that
    // it can make callbacks and pass along information about the
    // logged-in user.
    ApiProxy.setEnvironmentForCurrentThread(environment);

    if (!disableDeadlineTimers) {
      // The timing conventions here are a bit wonky, but this is what
      // the Python runtime does.
      log.info("Scheduling soft deadline in " + millisUntilSoftDeadline + " ms for " + requestId);
      token.addScheduledFuture(
          schedule(
              new DeadlineRunnable(this, token, false),
              millisUntilSoftDeadline));
    }

    // Start counting CPU cycles used by this thread.
 ////   timer.start();

    return token;
  }

  /**
   * Tear down any state associated with the specified request, and
   * restore the current thread's state as it was before {@code
   * startRequest} was called.
   *
   * @throws IllegalStateException if called from the wrong thread.
   */
  public void finishRequest(RequestToken requestToken) {
    verifyRequestAndThread(requestToken);

    // Don't let user code create any more threads.  This is
    // especially important for ThreadPoolExecutors, which will try to
    // backfill the threads that we're about to interrupt without user
    // intervention.
    requestToken.getState().setAllowNewRequestThreadCreation(false);

    // Interrupt any other request threads.
    for (Thread thread : getActiveThreads(requestToken.getRequestThreadGroup()).values()) {
      log.warning("Interrupting " + thread);
      thread.interrupt();
    }
    // Now wait for any async API calls and all request threads to complete.
    waitForUserCodeToComplete(requestToken);

    // There is no more user code left, stop the timers and tear down the state.
    requests.remove(requestToken.getSecurityTicket());
    requestToken.setFinished();

    // Stop the timer first so the user does get charged for our clean-up.
    CpuRatioTimer timer = requestToken.getRequestTimer();
    timer.stop();

    // Cancel any scheduled future actions associated with this
    // request.
    //
    // N.B.(schwardo): Copy the list to avoid a
    // ConcurrentModificationException due to a race condition where
    // the soft deadline runnable runs and adds the hard deadline
    // runnable while we are waiting for it to finish.  We don't
    // actually care about this race because we set
    // RequestToken.finished above and both runnables check that
    // first.
    for (Future<?> future : new ArrayList<Future<?>>(requestToken.getScheduledFutures())) {
      // Unit tests will fail if a future fails to execute correctly, but
      // we won't get a good error message if it was due to some exception.
      // Log a future failure due to exception here.
      if (future.isDone()) {
        try {
          future.get();
        } catch (Exception e) {
          log.log(Level.SEVERE, "Future failed execution: " + future, e);
        }
      } else if (future.cancel(false)) {
        log.fine("Removed scheduled future: " + future);
      } else {
        log.fine("Unable to remove scheduled future: " + future);
      }
    }

    // Store the CPU usage for this request in the UPResponse.
    log.info("Stopped timer for request: " + timer);
    requestToken.getUpResponse().setUserMcycles(
        timer.getCycleCount() / 1000000L);

    // If there is a (non-noop) profiler installed, stop it.
  //////  requestToken.getProfiler().stop(requestToken.getUpResponse());

    // Log runtime-collected stats, then clear them out
    RuntimeStats stats = RuntimeStats.getThreadLocalStats();
    stats.logStats();
    stats.clear();

    // Remove our environment information to remove any potential
    // for leakage.
    ApiProxy.clearEnvironmentForCurrentThread();

    runtimeLogSink.flushLogs(requestToken.getUpResponse());

    // Notify any shutdown request that we're done.
    synchronized (this) {
      notifyAll();
    }
  }

  public void sendDeadline(String securityTicket, boolean isUncatchable) {
    log.info("Looking up token for security ticket " + securityTicket);
    sendDeadline(requests.get(securityTicket), isUncatchable);
  }

  // Although Thread.stop(Throwable) is deprecated due to being
  // "inherently unsafe", it does exactly what we want.  Locks will
  // still be released (unlike Thread.destroy), so the only real
  // risk is that users are not expecting a particular piece of code
  // to throw an exception, and therefore when an exception is
  // thrown it leaves their objects in a bad state.  Since objects
  // should not be shared across requests, this should not be a very
  // big problem.
  @SuppressWarnings({"deprecation"})
  public void sendDeadline(RequestToken token, boolean isUncatchable) {
    if (token == null) {
      log.info("No token, can't send deadline");
      return;
    }
    checkForDeadlocks(token);
    final Thread targetThread = token.getRequestThread();
    log.info("Sending deadline: " + targetThread + ", " +
             token.getRequestId() + ", " + isUncatchable);
    // SimpleDateFormat isn't threadsafe so just instantiate as-needed
    final DateFormat dateFormat = new SimpleDateFormat(SIMPLE_DATE_FORMAT_STRING);
    // Give the user as much information as we can.
    final Throwable throwable = createDeadlineThrowable(
        "This request (" + token.getRequestId() + ") " +
        "started at " + dateFormat.format(token.getStartTimeMillis()) +
        " and was still executing at " +
        dateFormat.format(System.currentTimeMillis()) + ".",
        isUncatchable);
    // There is a weird race condition here.  We're throwing an
    // exception during the execution of an arbitrary method, but
    // that exception will contain the stack trace of what the
    // thread was doing a very small amount of time *before* the
    // exception was thrown (i.e. potentially in a different method).
    //
    // TODO(tobyr): Add a try-catch block to every instrumented
    // method, which catches this throwable (or an internal version
    // of it) and checks to see if the stack trace has the proper
    // class and method at the top.  If so, rethrow it (or a public
    // version of it).  If not, create a new exception with the
    // correct class and method, but with an unknown line number.
    //
    // N.B.(schwardo): Also, we're now using this stack trace to
    // determine when to terminate the clone.  The above issue may
    // cause us to terminate either too many or two few clones.  Too
    // many is merely wasteful, and too few is no worse than it was
    // without this change.
    StackTraceElement[] stackTrace = targetThread.getStackTrace();
    if (threadStopTerminatesClone || isUncatchable || inClassInitialization(stackTrace)) {
      // If we bypassed catch blocks or interrupted class
      // initialization, don't reuse this clone.
      token.getUpResponse().setTerminateClone(true);
    }

    throwable.setStackTrace(stackTrace);

    if (!token.isFinished()) {
      // Throw the exception in targetThread.
      AccessController.doPrivileged(new PrivilegedAction<Object>() {
          public Object run() {
            targetThread.stop(throwable);
            return null;
          }
      } );
    }

    if (isUncatchable) {
      token.getState().setHardDeadlinePassed(true);
    } else {
      token.getState().setSoftDeadlinePassed(true);
    }
  }

  private void waitForUserCodeToComplete(RequestToken requestToken) {
    RequestState state = requestToken.getState();
    if (Thread.interrupted()) {
      log.info("Interrupt bit set in waitForUserCodeToComplete, resetting.");
      // interrupted() already reset the bit.
    }

    try {
      if (state.hasHardDeadlinePassed()) {
        log.info("Hard deadline has already passed; skipping wait for async futures.");
      } else {
        // Wait for async API calls to complete.  Don't bother doing
        // this if the hard deadline has already passed, we're not going to
        // reuse this JVM anyway.
        waitForPendingAsyncFutures(requestToken.getAsyncFutures());
      }

      // Now wait for any request-scoped threads to complete.
      Collection<Thread> threads;
      while (!(threads = getActiveThreads(requestToken.getRequestThreadGroup()).values())
             .isEmpty()) {
        if (state.hasHardDeadlinePassed()) {
          StringBuilder message = new StringBuilder("Thread(s) still running after request:\n");
          for (Thread thread : threads) {
            message.append(thread + "\n");
            for (StackTraceElement element : thread.getStackTrace()) {
              message.append("... " + element + "\n");
            }
            message.append("\n");
          }
    ////      requestToken.getUpResponse().setError(UPResponse.ERROR.THREADS_STILL_RUNNING.getValue());
          requestToken.getUpResponse().clearHttpResponse();
          String messageString = message.toString();
          log.warning(messageString);
          requestToken.addAppLogMessage(ApiProxy.LogRecord.Level.fatal, messageString);
          return;
        } else {
          try {
            // Interrupt the threads one more time before joining.
            // This helps with ThreadPoolExectors, where the first
            // interrupt may cancel the current runnable but another
            // interrupt is needed to kill the (now-idle) worker
            // thread.
            for (Thread thread : threads) {
              thread.interrupt();
            }
            for (Thread thread : threads) {
              log.info("Waiting for completion of thread: " + thread);
              thread.join();
            }
            log.info("All request threads have completed.");
          } catch (DeadlineExceededException ex) {
            // expected, try again.
          } catch (HardDeadlineExceededError ex) {
            // expected, loop around and we'll do something else this time.
          }
        }
      }
    } catch (Throwable ex) {
      log.log(Level.WARNING, "Exception thrown while waiting for background work to complete:", ex);
    }
  }

  private void waitForPendingAsyncFutures(Collection<Future<?>> asyncFutures)
      throws InterruptedException {
    int size = asyncFutures.size();
    if (size > 0) {
      log.warning("Waiting for " + size + " pending async futures.");
      for (Future<?> future : asyncFutures) {
        // Unlike scheduled futures, we do *not* want to cancel these
        // futures if they aren't done yet.  They represent asynchronous
        // actions that the user began which we still want to succeed.
        // We simply need to block until they do.
        try {
          // Don't bother specifying a deadline --
          // DeadlineExceededException's will break us out of here if
          // necessary.
          future.get();
        } catch (ExecutionException ex) {
          log.log(Level.INFO, "Async future failed:", ex.getCause());
        }
      }
      log.warning("Done waiting for pending async futures.");
    }
  }

  /**
   * Returns all of the active threads in {@code threadGroup} except
   * the current thread.
   */
  private Map<Long, Thread> getActiveThreads(ThreadGroup threadGroup) {
    Thread currentThread = Thread.currentThread();
    Thread[] threadArray = new Thread[MAX_THREADS];
    int activeThreads = threadGroup.enumerate(threadArray, true);
    Map<Long, Thread> map = new HashMap<Long, Thread>(activeThreads);
    for (int i = 0; i < activeThreads; i++) {
      if (threadArray[i] != currentThread) {
        map.put(threadArray[i].getId(), threadArray[i]);
      }
    }
    return map;
  }

  /**
   * Check that the current thread matches that called {@link
   * startRequest()}.
   * @throws IllegalStateException If called from the wrong thread.
   */
  private void verifyRequestAndThread(RequestToken requestToken) {
    if (requestToken.getRequestThread() != Thread.currentThread()) {
      throw new IllegalStateException(
          "Called from " + Thread.currentThread() + ", should be " +
          requestToken.getRequestThread());
    }
  }

  /**
   * Arrange for the specified {@code Runnable} to be executed in
   * {@code time} milliseconds.
   */
  private Future<?> schedule(Runnable runnable, long time) {
    log.fine("Scheduling " + runnable + " to run in " + time + " ms.");
    return executor.schedule(runnable, time, TimeUnit.MILLISECONDS);
  }

  /**
   * Adjusts the deadline for this RPC by the padding constant along with the
   * elapsed time.  Will return the defaultValue if the rpc is not valid.
   */
  private long getAdjustedRpcDeadline(RpcServerContext rpc, long defaultValue) {
    if (rpc.getDeadline() == Double.POSITIVE_INFINITY || rpc.getStartTimeMillis() == 0) {
      log.warning("Did not receive enough RPC information to calculate adjusted " +
                  "deadline: " + rpc);
      return defaultValue;
    }

    long elapsedMillis = System.currentTimeMillis() - rpc.getStartTimeMillis();

    if (rpc.getDeadline() < 600000000L) {
      log.warning("RPC deadline is less than padding.  Not adjusting deadline");
      return (long) rpc.getDeadline() * 1000L - elapsedMillis;
    } else {
      return (long) (rpc.getDeadline() - 6000000L) * 1000L
                    - elapsedMillis;
    }
  }

  /**
   * Notify requests that the server is shutting down.
   */
  public void shutdownRequests(RequestToken token) {
    checkForDeadlocks(token);
    log.info("Calling shutdown hooks for " + token.getAppVersionKey());
    // TODO what if there's other app/versions in this VM?
    UPResponse response = token.getUpResponse();
    LifecycleManager.getInstance().beginShutdown(token.getDeadline());
    
    logMemoryStats();
    
    logAllStackTraces();
    
   //// response.setError(UPResponse.ERROR.OK.getValue());
    HttpResponse httpResponse = response.getMutableHttpResponse();
    httpResponse.setResponsecode(200);
    httpResponse.setResponse("OK");
  }

  private int getRequestCount(AppVersionKey appVersionKey) {
    int count = 0;
    synchronized (requests) {
      for (RequestToken token : requests.values()) {
        if (appVersionKey.equals(token.getAppVersionKey())) {
          count += 1;
        }
      }
    }
    return count;
  }

  List<Thread> getRequestThreads(AppVersionKey appVersionKey) {
    List<Thread> threads = new ArrayList<Thread>();
    synchronized (requests) {
      for (RequestToken token : requests.values()) {
        if (appVersionKey.equals(token.getAppVersionKey())) {
          threads.add(token.getRequestThread());
        }
      }
    }
    return threads;
  }

  /**
   * Consults {@link ThreadMXBean#findDeadlockedThreads()} to see if
   * any deadlocks are currently present.  If so, it will
   * immediately respond to the runtime and simulate a LOG(FATAL)
   * containing the stack trace of the offending threads.
   */
  private void checkForDeadlocks(final RequestToken token) {
    AccessController.doPrivileged(new PrivilegedAction<Object>() {
        public Object run() {
          long[] deadlockedThreadsIds = THREAD_MX.findDeadlockedThreads();
          if (deadlockedThreadsIds != null) {
            ThreadGroup root = token.getAppVersion().getRootThreadGroup();
            Map<Long, Thread> activeThreads = getActiveThreads(root);

            StringBuilder builder = new StringBuilder();
            builder.append("Detected a deadlock across " + deadlockedThreadsIds.length +
                           " threads:");
            boolean allThreadsAreUserThreads = true;
            for (ThreadInfo info : THREAD_MX.getThreadInfo(deadlockedThreadsIds,
                                                           MAXIMUM_DEADLOCK_STACK_LENGTH)) {
              builder.append(info);
              builder.append("\n");
              Thread thread = activeThreads.get(info.getThreadId());
              if (thread == null) {
                allThreadsAreUserThreads = false;
              }
            }
            String message = builder.toString();
            if (allThreadsAreUserThreads) {
              // TODO(schwardo): Scrub stack traces.
              token.addAppLogMessage(ApiProxy.LogRecord.Level.fatal, message);
            }
            token.logAndKillRuntime(message);
          }
          return null;
        }
    });
  }
  
  private void logMemoryStats() {
    Runtime runtime = Runtime.getRuntime();
    log.info("maxMemory=" + runtime.maxMemory() + " totalMemory=" + runtime.totalMemory() + 
        " freeMemory=" + runtime.freeMemory());
  }
  
  private void logAllStackTraces() {
    AccessController.doPrivileged(new PrivilegedAction<Object>() {
      public Object run() {
        long[] allthreadIds = THREAD_MX.getAllThreadIds();
        StringBuilder builder = new StringBuilder();
        builder.append("Dumping thread info for all " + allthreadIds.length +
                       " runtime threads:");
        for (ThreadInfo info : THREAD_MX.getThreadInfo(allthreadIds,
                                                       MAXIMUM_DEADLOCK_STACK_LENGTH)) {
          builder.append(info);
          builder.append("\n");
        }
        String message = builder.toString();
        log.info(message);
        return null;
      }
    });
  }

  private Throwable createDeadlineThrowable(String message, boolean isUncatchable) {
    if (isUncatchable) {
      return new HardDeadlineExceededError(message);
    } else {
      return new DeadlineExceededException(message);
    }
  }

  private boolean inClassInitialization(StackTraceElement[] stackTrace) {
    for (StackTraceElement element : stackTrace) {
      if ("<clinit>".equals(element.getMethodName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code RequestToken} acts as a Memento object that passes state
   * between a call to {@code startRequest} and {@code finishRequest}.
   * It should be treated as opaque by clients.
   */
  public static class RequestToken {
    /**
     * The thread of the request.  This is used to verify that {@code
     * finishRequest} was called from the right thread.
     */
    private final Thread requestThread;

    private final UPResponse upResponse;

    /**
     * A collection of {@code Future} objects that have been scheduled
     * on behalf of this request.  These futures will each be
     * cancelled when the request completes.
     */
    private final Collection<Future<?>> scheduledFutures;

    private final Collection<Future<?>> asyncFutures;

    private final String requestId;

    private final String securityTicket;

    /**
     * A {@code Timer} that runs during the course of the request and
     * measures both wallclock and CPU time.
     */
    private final CpuRatioTimer requestTimer;

  //  private final Profiler profiler;

    private volatile boolean finished;

    private final AppVersion appVersion;

    private final long deadline;

    private final RpcServerContext rpc;
    private final long startTimeMillis;
    private final ThreadGroup requestThreadGroup;

    private final RequestState state;

    RequestToken(Thread requestThread, UPResponse upResponse,
                 String requestId, String securityTicket, CpuRatioTimer requestTimer,
                 Collection<Future<?>> asyncFutures, AppVersion appVersion,
                 long deadline, RpcServerContext rpc, long startTimeMillis,
                  ThreadGroup requestThreadGroup, RequestState state) {
      this.requestThread = requestThread;
      this.upResponse = upResponse;
      this.requestId = requestId;
      this.securityTicket = securityTicket;
      this.requestTimer = requestTimer;
      this.asyncFutures = asyncFutures;
      this.scheduledFutures = new ArrayList<Future<?>>();
      this.finished = false;
      this.appVersion = appVersion;
      this.deadline = deadline;
      this.rpc = rpc;
      this.startTimeMillis = startTimeMillis;
      this.requestThreadGroup = requestThreadGroup;
      this.state = state;
    }

    public RequestState getState() {
      return state;
    }

    Thread getRequestThread() {
      return requestThread;
    }

    UPResponse getUpResponse() {
      return upResponse;
    }

    CpuRatioTimer getRequestTimer() {
      return requestTimer;
    }

    public String getRequestId() {
      return requestId;
    }

    public String getSecurityTicket() {
      return securityTicket;
    }

    public AppVersion getAppVersion() {
      return appVersion;
    }

    public AppVersionKey getAppVersionKey() {
      return appVersion.getKey();
    }

    public long getDeadline() {
      return deadline;
    }

    public long getStartTimeMillis() {
      return startTimeMillis;
    }

    Collection<Future<?>> getScheduledFutures() {
      return scheduledFutures;
    }

    void addScheduledFuture(Future<?> future) {
      scheduledFutures.add(future);
    }

    Collection<Future<?>> getAsyncFutures() {
      return asyncFutures;
    }



    boolean isFinished() {
      return finished;
    }

    void setFinished() {
      finished = true;
    }

    public void addAppLogMessage(ApiProxy.LogRecord.Level level, String message) {
      AppLogLine logLine = upResponse.addAppLog();
      logLine.setLevel(level.ordinal());
      logLine.setTimestampUsec(System.currentTimeMillis() * 1000);
      logLine.setMessage(message);
    }

    void logAndKillRuntime(String errorMessage) {
      log.severe("LOG(FATAL): " + errorMessage);
      upResponse.clearHttpResponse();
 //     upResponse.setError(UPResponse.ERROR.LOG_FATAL_DEATH.getValue());
      upResponse.setErrorMessage(errorMessage);
      rpc.finishWithResponse(upResponse);
    }

    ThreadGroup getRequestThreadGroup() {
      return requestThreadGroup;
    }
  }

  /**
   * {@code DeadlineRunnable} causes the specified {@code Throwable}
   * to be thrown within the specified thread.  The stack trace of the
   * Throwable is ignored, and is replaced with the stack trace of the
   * thread at the time the exception is thrown.
   */
  public class DeadlineRunnable implements Runnable {
    private final RequestManager requestManager;
    private final RequestToken token;
    private final boolean isUncatchable;

    public DeadlineRunnable(RequestManager requestManager, RequestToken token,
                            boolean isUncatchable) {
      this.requestManager = requestManager;
      this.token = token;
      this.isUncatchable = isUncatchable;
    }

    public void run() {
      requestManager.sendDeadline(token, isUncatchable);

      if (!token.isFinished()) {
        if (!isUncatchable) {
          token.addScheduledFuture(
              schedule(
                  new DeadlineRunnable(requestManager, token, true),
                  softDeadlineDelay - hardDeadlineDelay));
        }

        log.info("Finished execution of " + this);
      }
    }

    public String toString() {
      return "DeadlineRunnable(" + token.getRequestThread() + ", " + token.getRequestId() +
          ", " + isUncatchable + ")";
    }
  }
}
