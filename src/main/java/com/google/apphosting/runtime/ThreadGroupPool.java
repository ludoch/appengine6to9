
package com.google.apphosting.runtime;

import com.google.common.base.Preconditions;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Queue;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ThreadGroupPool {
  private static final Logger logger =
      Logger.getLogger(ThreadGroupPool.class.getName());

  public static final int MAX_THREADS_PER_THREAD_GROUP = 50;

  private final ThreadGroup parentThreadGroup;
  private final String threadGroupNamePrefix;
  private final AtomicInteger threadGroupCounter;
  private final Queue<PoolEntry> waitingThreads;
  private final UncaughtExceptionHandler uncaughtExceptionHandler;

  public ThreadGroupPool(ThreadGroup parentThreadGroup,
                         String threadGroupNamePrefix,
                         UncaughtExceptionHandler uncaughtExceptionHandler) {
    this.parentThreadGroup = Preconditions.checkNotNull(parentThreadGroup);
    this.threadGroupNamePrefix = Preconditions.checkNotNull(threadGroupNamePrefix);
    this.threadGroupCounter = new AtomicInteger(0);
    this.waitingThreads = new ConcurrentLinkedQueue<PoolEntry>();
    this.uncaughtExceptionHandler = Preconditions.checkNotNull(uncaughtExceptionHandler);
  }

  /**
   * Execute {@code runnable} in a thread named {@code threadName}.
   * This may be a newly created thread or it may be a thread that was
   * was already used to run one or more previous invocations.
   *
   * <p>{@code runnable} can spawn other threads in the pooled
   * {@link ThreadGroup}, but they must all exit before the runnable
   * completes.  Failure of the extra threads to complete will result
   * in a severe log message and the dropping of this thread from the
   * pool.
   *
   * <p>This method will block until the thread begins executing
   * {@code runnable}.  If executing {@link Runnable#run} on
   * {@code runnable} throws an exception, the thread will not be
   * returned to the thread pool.
   */
  public void start(String threadName, Runnable runnable) throws InterruptedException {
    PoolEntry entry = waitingThreads.poll();
    if (entry == null) {
      entry = buildPoolEntry();
    }
    initThread(entry.getMainThread(), threadName);
    entry.runInMainThread(runnable);
  }

  /**
   * Interrupts and waits for completion of all waiting threads.
   * Makes no attempt to interrupt threads that are currently running.
   */
  public void shutdown() throws InterruptedException {
    Collection<PoolEntry> entries = new ArrayList<PoolEntry>(waitingThreads);
    for (PoolEntry entry : entries) {
      Thread thread = entry.getMainThread();
      thread.interrupt();
    }
    for (PoolEntry entry : entries) {
      Thread thread = entry.getMainThread();
      thread.join();
    }
  }

  private void removeThread(PoolEntry entry) {
    waitingThreads.remove(entry);
  }

  private void returnThread(PoolEntry entry) {
    initThread(entry.getMainThread(), "Idle");
    waitingThreads.add(entry);
  }

  private void initThread(Thread thread, String threadName) {
    thread.setName(threadName);
    thread.setUncaughtExceptionHandler(null);
  }

  private PoolEntry buildPoolEntry() {
    String name = threadGroupNamePrefix + threadGroupCounter.getAndIncrement();
    ThreadGroup threadGroup = new ThreadGroup(parentThreadGroup, name) {
        @Override
        public void uncaughtException(Thread th, Throwable ex) {
          uncaughtExceptionHandler.uncaughtException(th, ex);
        }
    };
    PoolEntry entry = new PoolEntry(threadGroup);
    entry.startMainThread();
    return entry;
  }

  /**
   * If the current thread is main thread started in response to a
   * call to {@link #start}, this method will arrange for it to expect
   * to be "restarted."  See {@link RestartableThread} for more
   * information.
   *
   * @throws IllegalStateException If the current thread is not a main
   * thread.
   */
  public static CountDownLatch resetCurrentThread() throws InterruptedException {
    Thread thread = Thread.currentThread();
    if (thread instanceof RestartableThread) {
      return ((RestartableThread) thread).reset();
    } else {
      throw new IllegalStateException("Current thread is not a main request thread.");
    }
  }

  /**
   * {@code RestartableThread} is a thread that can be put to sleep
   * until {@link Thread#start} is called again.  This is required for
   * background threads, which will be spawned normally and passed to
   * user code, but then need to block until user code invokes the
   * start method before proceeding.  To facilitate this, calling code
   * needs to invoke {@link #reset} before returning the
   * thread to user code, and it can block on the returned latch to be
   * awoke when user code calls start.  Note that subsequent start
   * calls will behave normally, including throwing an
   * {@link IllegalStateException} when appropriate.
   */
  private static final class RestartableThread extends Thread {
    private final Object lock = new Object();
    private CountDownLatch latch;

    public RestartableThread(ThreadGroup threadGroup, Runnable runnable) {
      super(threadGroup, runnable);
    }

    public CountDownLatch reset() throws InterruptedException {
      synchronized (lock) {
        latch = new CountDownLatch(1);
        return latch;
      }
    }

    @Override
    public void start() {
      synchronized (lock) {
        if (latch != null) {
          latch.countDown();
          latch = null;
          return;
        }
      }
      // No reset was pending, do the normal thing.
      super.start();
    }

    @Override
    public Thread.State getState() {
      synchronized (lock) {
        if (latch != null) {
          // Thread has been reset, pretend it is not yet started.
          return Thread.State.NEW;
        }
      }
      return super.getState();
    }
  }

  /**
   * {@code PoolEntry} is one entry in a {@link ThreadGroupPool} that
   * consists of a {@link ThreadGroup}, a single {@link Thread} within
   * that group, and an {@link Exchanger} that is used to pass a
   * {@link Runnable} into the thread for execution.  The entry itself
   * serves as a {@link Runnable} that forwards control the the
   * {@link Runnable} received via the {@link Executor}, and then
   * verifies that no other threads remain in the {@link ThreadGroup}
   * before returning it to the pool.
   */
  private class PoolEntry implements Runnable {
    final ThreadGroup threadGroup;

    /**
     * This Exchanger is passed Runnables from the thread calling (via
     * {@link #start}) to one of the pooled threads waiting to execute
     * the Runnable.  The value exchanged for the Runnable is not
     * used, and as a convention is {@code null}.
     */
    private final Exchanger<Runnable> exchanger;

    private final RestartableThread mainThread;

    PoolEntry(ThreadGroup threadGroup) {
      this.threadGroup = threadGroup;
      this.exchanger = new Exchanger<Runnable>();

      mainThread = new RestartableThread(threadGroup, this);
      mainThread.setDaemon(true);
    }

    void startMainThread() {
      mainThread.start();
    }

    Thread getMainThread() {
      return mainThread;
    }

    void runInMainThread(Runnable runnable) throws InterruptedException {
      if (!mainThread.isAlive()) {
        throw new IllegalStateException("Main thread is not running.");
      }
      exchanger.exchange(runnable);
    }

    public void run() {
      while (true) {
        Runnable runnable;
        try {
          runnable = exchanger.exchange(null);
        } catch (InterruptedException ex) {
          logger.info("Interrupted while waiting for next Runnable: " + ex);
          removeThread(this);
          return;
        }
        runnable.run();
        if (otherThreadsLeftInThreadGroup()) {
          return;
        }
        if (Thread.interrupted()) {
          logger.info("Not reusing " + this + ", interrupt bit was set.");
          return;
        }
        returnThread(this);
      }
    }

    /**
     * Verifies that no other active threads are present in
     * {@code threadGroup}.  If any threads are still running, log
     * their stack trace and return {@code true}.
     */
    private boolean otherThreadsLeftInThreadGroup() {
      Thread[] threads = new Thread[MAX_THREADS_PER_THREAD_GROUP];
      boolean otherThreads = false;
      int threadCount = threadGroup.enumerate(threads, true);
      if (threadCount != 1) {
        // Make sure we don't iterate past threadCount, the rest of
        // the elements will be null.
        for (int i = 0; i < threadCount; i++) {
          Thread thread = threads[i];
          if (thread != Thread.currentThread()) {
            Throwable th = new Throwable();
            th.setStackTrace(thread.getStackTrace());
            logger.log(Level.SEVERE, "Extra thread left running: " + thread, th);
            otherThreads = true;
          }
        }
      }
      return otherThreads;
    }
  }
}
