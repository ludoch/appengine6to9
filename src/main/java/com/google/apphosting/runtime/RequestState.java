
package com.google.apphosting.runtime;

public class RequestState {
  private boolean allowNewRequestThreadCreation = true;
  private boolean softDeadlinePassed = false;
  private boolean hardDeadlinePassed = false;

  public synchronized boolean getAllowNewRequestThreadCreation() {
    return allowNewRequestThreadCreation;
  }

  public synchronized void setAllowNewRequestThreadCreation(boolean allowNewRequestThreadCreation) {
    this.allowNewRequestThreadCreation = allowNewRequestThreadCreation;
  }

  public synchronized boolean hasSoftDeadlinePassed() {
    return softDeadlinePassed;
  }

  public synchronized void setSoftDeadlinePassed(boolean softDeadlinePassed) {
    this.softDeadlinePassed = softDeadlinePassed;
  }

  public synchronized boolean hasHardDeadlinePassed() {
    return hardDeadlinePassed;
  }

  public synchronized void setHardDeadlinePassed(boolean hardDeadlinePassed) {
    this.hardDeadlinePassed = hardDeadlinePassed;
  }
}
