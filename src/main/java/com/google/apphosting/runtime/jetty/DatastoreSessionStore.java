package com.google.apphosting.runtime.jetty;

import static com.google.apphosting.runtime.SessionManagerUtil.deserialize;
import static com.google.apphosting.runtime.SessionManagerUtil.serialize;

import java.util.Map;
import java.util.logging.Logger;

import com.google.appengine.api.NamespaceManager;
import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.DatastoreTimeoutException;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.apphosting.runtime.SessionData;
import com.google.apphosting.runtime.SessionStore;

/**
 * A {@link SessionStore} implementation on top of the datastore.
 *
 */
class DatastoreSessionStore implements SessionStore {

  private static final Logger logger =
      Logger.getLogger(DatastoreSessionStore.class.getName());

  static final String SESSION_ENTITY_TYPE = "_ah_SESSION";
  static final String EXPIRES_PROP = "_expires";
  static final String VALUES_PROP = "_values";

  private final DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

  /**
   * Return a {@link Key} for the given session "key" string
   * ({@link SessionManager#SESSION_PREFIX} + sessionId) in the empty namespace.
   */
  static Key createKeyForSession(String key) {
    String originalNamespace = NamespaceManager.get();
    try {
      NamespaceManager.set("");
      return KeyFactory.createKey(SESSION_ENTITY_TYPE, key);
    } finally {
      NamespaceManager.set(originalNamespace);
    }
  }


  static SessionData createSessionFromEntity(Entity entity) {
    SessionData data = new SessionData();
    data.setExpirationTime((Long) entity.getProperty(EXPIRES_PROP));

    Blob valueBlob = (Blob) entity.getProperty(VALUES_PROP);
    data.setValueMap((Map<String, Object>) deserialize(valueBlob.getBytes()));
    return data;
  }

  /**
   * Return an {@link Entity} for the given key and data in the empty
   * namespace.
   */
  static Entity createEntityForSession(String key, SessionData data) {
    String originalNamespace = NamespaceManager.get();
    try {
      NamespaceManager.set("");
      Entity entity = new Entity(SESSION_ENTITY_TYPE, key);
      entity.setProperty(EXPIRES_PROP, data.getExpirationTime());
      entity.setProperty(VALUES_PROP, new Blob(serialize(data.getValueMap())));
      return entity;
    } finally {
      NamespaceManager.set(originalNamespace);
    }
  }

  @Override
  public SessionData getSession(String key) {
    try {
      Entity entity = datastore.get(createKeyForSession(key));
      logger.info("Loaded session " + key + " from datastore.");
      return createSessionFromEntity(entity);
    } catch (EntityNotFoundException ex) {
      logger.info("Unable to find specified session " + key);
    }
    return null;
  }

  @Override
  public void saveSession(String key, SessionData data) throws Retryable {
    try {
      datastore.put(createEntityForSession(key, data));
    } catch (DatastoreTimeoutException e) {
      throw new Retryable(e);
    }
  }

  @Override
  public void deleteSession(String key) {
    datastore.delete(createKeyForSession(key));
  }
}
