package com.google.apphosting.runtime;


/**
 * Http Sessions config options.
 *
 */
public class SessionsConfig {
  private final boolean enabled;
  private final boolean asyncPersistence;
  private final String asyncPersistenceQueueName;

  public SessionsConfig(boolean enabled, boolean asyncPersistence,
      String asyncPersistenceQueueName) {
    this.enabled = enabled;
    this.asyncPersistence = asyncPersistence;
    this.asyncPersistenceQueueName = asyncPersistenceQueueName;
  }

  /**
   * Returns true if sessions are enabled.  Otherwise, for JSPs which always
   * use getSessions(), we'll make a dummy session object, but not allow real
   * operations on it.
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns true if sessions are asynchronously written to the datastore.
   */
  public boolean isAsyncPersistence() {
    return asyncPersistence;
  }

  /**
   * Returns the name of the queue to use for async session persistence.  If
   * {@code null}, the default queue will be used.
   */
  public String getAsyncPersistenceQueueName() {
    return asyncPersistenceQueueName;
  }
}
