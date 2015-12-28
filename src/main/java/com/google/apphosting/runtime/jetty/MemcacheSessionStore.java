package com.google.apphosting.runtime.jetty;

import static com.google.apphosting.runtime.SessionManagerUtil.deserialize;
import static com.google.apphosting.runtime.SessionManagerUtil.serialize;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.appengine.api.memcache.ErrorHandlers;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.SessionData;
import com.google.apphosting.runtime.SessionStore;

/**
 * A {@link SessionStore} implementation on top of memcache.
 * 
 */
class MemcacheSessionStore implements SessionStore {

  private static final Logger logger =
      Logger.getLogger(MemcacheSessionStore.class.getName());

  private final MemcacheService memcache;

  public MemcacheSessionStore() {
    memcache = MemcacheServiceFactory.getMemcacheService("");
    memcache.setErrorHandler(ErrorHandlers.getConsistentLogAndContinue(Level.INFO));
  }

  @Override
  public SessionData getSession(String key) {
    byte[] sessionBytes = (byte[]) memcache.get(key);
    if (sessionBytes != null) {
      logger.info("Loaded session " + key + " from memcache.");
      return (SessionData) deserialize(sessionBytes);
    }
    return null;
  }

  @Override
  public void saveSession(String key, SessionData data) throws Retryable {
    try {
      memcache.put(key, serialize(data));
    } catch (ApiProxy.ApiDeadlineExceededException e) {
      throw new Retryable(e);
    }
  }

  @Override
  public void deleteSession(String key) {
    memcache.delete(key);
  }
}
