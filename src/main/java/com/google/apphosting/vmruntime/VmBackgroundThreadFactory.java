package com.google.apphosting.vmruntime;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;

import java.util.concurrent.ThreadFactory;

/**
 * Thread factory creating background threads with the default thread local environment.
 *
 */
public class VmBackgroundThreadFactory implements ThreadFactory {
  private final Environment defaultEnvironment;

  /**
   * Create a new VmBackgroundThreadFactory.
   *
   * @param defaultEnvironment The environment to install on each thread.
   */
  public VmBackgroundThreadFactory(Environment defaultEnvironment) {
    this.defaultEnvironment = defaultEnvironment;
  }

  /**
   * Create a new {@link Thread} that executes {@code runnable} independent of the current request.
   *
   * @param runnable The object whose run method is invoked when this thread is started.
   */
  @Override
  public Thread newThread(final Runnable runnable) {
    return new Thread(new Runnable() {
      @Override
      public void run() {
        ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
        runnable.run();
      }
    });
  }
}
