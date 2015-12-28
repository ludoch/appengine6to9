package com.google.apphosting.vmruntime;


/**
 * Minimal implementation of com.google.apphosting.runtime.timer.Timer using only the system clock.
 *
 */
public class VmTimer extends AbstractIntervalTimer {

  /*
   * (non-Javadoc)
   *
   * @see com.google.apphosting.runtime.timer.AbstractIntervalTimer#getCurrent()
   */
  protected long getCurrent() {
    return System.nanoTime();
  }
}
