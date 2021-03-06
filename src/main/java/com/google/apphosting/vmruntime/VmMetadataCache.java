// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.apphosting.vmruntime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A class to retrieve and cache the meta-data of a VM running in Google's Compute Engine.
 *
 */
public class VmMetadataCache {

  private static final Logger logger = Logger.getLogger(VmMetadataCache.class.getCanonicalName());

  /** The meta-data server's URL prefix. */
  public static final String DEFAULT_META_DATA_SERVER = "metadata";
  public static final String META_DATA_PATTERN = "http://%s/0.1/meta-data/%s";

  /** Maps paths to their cached values (null if a previous retrieval attempt failed). */
  private final Map<String, String> cache;

  /** Timeout in milliseconds to retrieve data from the server. */
  private static final int TIMEOUT_MILLIS = 120 * 1000;

  public VmMetadataCache() {
    cache = new HashMap<String, String>();
  }

  /**
   * Returns the value of the VM's meta-data attribute, or null if retrieval has failed.
   *
   * @param path the meta-data attribute to be retrieved (e.g. "image", "attributes/sshKeys").
   * @return the attribute's string value or null if retrieval has failed.
   */
  public String getMetadata(String path) {
    synchronized (cache) {
      if (cache.containsKey(path)) {
        return cache.get(path);
      }
    }

    String value = null;
    try {
      // It is safe to concurrently retrieve the same server path.
      value = getMetadataFromServer(path);

      // We cache missing attributes (404) as null values.
      synchronized (cache) {
        cache.put(path, value);
      }
    } catch (IOException e) {
      // Don't cache the value if we have failed to connect or transfer.
      logger.info("Meta-data '" + path + "' path retrieval error: " + e.getMessage());
    }
    return value;
  }

  /**
   * Clears all cached meta-data values.
   */
  public void clear() {
    synchronized (cache) {
      cache.clear();
    }
  }

  /**
   * Returns an HTTP URL connection to read the value of the specified attribute.
   *
   * May be overridden in tests.
   *
   * @param path the meta-data attribute to be retrieved (e.g. "image", "attributes/sshKeys").
   * @return the HTTP URL connection object.
   * @throws IOException if the connection could not be opened.
   */
  protected HttpURLConnection openConnection(String path) throws IOException {
    String server = System.getProperty("metadata_server", DEFAULT_META_DATA_SERVER);
    URL url = new URL(String.format(META_DATA_PATTERN, server, path));
    return (HttpURLConnection) url.openConnection();
  }

  /**
   * Retrieves the specified path from the VM's meta-data server.
   *
   * @param path a path in the meta-data server (e.g. "image").
   * @return the meta-data's string value or null if the attribute is not found.
   * @throws IOException if the connection to the meta-data server fails, or refused.
   */
  protected String getMetadataFromServer(String path) throws IOException {
    BufferedReader reader = null;
    HttpURLConnection connection = null;
    try {
      connection = openConnection(path);
      connection.setConnectTimeout(TIMEOUT_MILLIS);
      connection.setReadTimeout(TIMEOUT_MILLIS);
      reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
      StringBuffer result = new StringBuffer();
      char[] buffer = new char[4096];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        result.append(buffer, 0, read);
      }
      if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
        return result.toString().trim();
      } else if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
        return null;
      }
      throw new IOException("Meta-data request for '" + path + "' failed with error: " +
          connection.getResponseMessage());
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          logger.info("Error closing connection for " + path + ": " + e.getMessage());
        }
      }
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
