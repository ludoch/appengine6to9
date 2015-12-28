
package com.google.apphosting.runtime;

import java.util.HashMap;
import java.util.Map;
import java.io.Serializable;

/**
 * {@code SessionData} is a simple data container for the contents of
 * a {@link javax.servlet.http.HttpSession}.  It must be shared
 * between user and runtime code, as it is deserialized in the context
 * of a user class loader.
 *
 */
public class SessionData implements Serializable {
  private Map<String, Object> valueMap;
  private long expirationTime;

  public SessionData() {
    valueMap = new HashMap<String, Object>();
  }

  public long getExpirationTime() {
    return expirationTime;
  }

  public void setExpirationTime(long expirationTime) {
    this.expirationTime = expirationTime;
  }

  public Map<String, Object> getValueMap() {
    return valueMap;
  }

  public void setValueMap(Map<String, Object> valueMap) {
    this.valueMap = valueMap;
  }
}
