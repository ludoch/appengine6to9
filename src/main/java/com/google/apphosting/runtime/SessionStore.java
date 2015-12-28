package com.google.apphosting.runtime;

/**
 * Describes an object that knows how to lookup, save, and delete
 * session data.
 *
 */
public interface SessionStore {
  SessionData getSession(String key);
  void saveSession(String key, SessionData data) throws Retryable;
  void deleteSession(String key);

  /**
   * Indicates that the operation can be retried.
   */
  class Retryable extends Exception {
    public Retryable(RuntimeException cause) {
      super(cause);
    }

    @Override
    public RuntimeException getCause() {
      return (RuntimeException) super.getCause();
    }
  }
}
