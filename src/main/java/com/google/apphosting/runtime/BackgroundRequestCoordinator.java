
package com.google.apphosting.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.logging.Logger;


public class BackgroundRequestCoordinator {
  private static final Logger logger =
      Logger.getLogger(BackgroundRequestCoordinator.class.getName());

  /**
   * Map from request identifiers to an {@link Exchanger} that can be
   * used to exchange a user-supplied {@link Runnable} for the
   * {@link Thread} on which the user code should run.  All access to
   * this map must be synchronized, which can be done by calling
   * {@link #getExchanger}.
   */
  private final Map<String, Exchanger<Object>> exchangerMap;

  public BackgroundRequestCoordinator() {
    exchangerMap = new HashMap<String, Exchanger<Object>>();
  }

  /**
   * Wait for the fake request with the specified {@code requestId} to
   * call {@link #waitForUserRunnable} and then exchange
   * {@code runnable} for the specified {@link Thread}.
   */
  public Thread waitForThreadStart(String requestId, Runnable runnable)
      throws InterruptedException {
    logger.info("Waiting until thread creation for " + requestId);
    return (Thread) getExchanger(requestId).exchange(runnable);
  }

  /**
   * Wait for the system API call with the specified {@code requestId}
   * to call {@link #waitForThreadStart} and then exchange
   * {@code thread} for the specified {@link Runnable}.
   */
  public Runnable waitForUserRunnable(String requestId, Thread thread)
      throws InterruptedException {
    logger.info("Got thread creation for " + requestId);
    return (Runnable) getExchanger(requestId).exchange(thread);
  }

  /**
   * Look up the {@link Exchanger} for the specified request.  If none
   * is available, one is atomically created.
   */
  private synchronized Exchanger<Object> getExchanger(String requestId) {
    Exchanger<Object> exchanger = exchangerMap.get(requestId);
    if (exchanger == null) {
      exchanger = new Exchanger<Object>();
      exchangerMap.put(requestId, exchanger);
    }
    return exchanger;
  }
}
