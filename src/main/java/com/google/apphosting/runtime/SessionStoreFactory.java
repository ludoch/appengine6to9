
package com.google.apphosting.runtime;

import java.util.List;

/**
 * A Factory for creating {@link SessionStore SessionStores}.
 *
 */
public interface SessionStoreFactory {

  /**
   * Returns a list of {@code SessionStores}, in write order. The session will be
   * written to the stores in the order returned from this method.
   */
  public List<SessionStore> createSessionStores(SessionsConfig sessionsConfig);

}
