
package com.google.apphosting.runtime;

import java.util.Map;
import java.util.HashMap;


public class ApiDeadlineOracle {
  private final DeadlineMap deadlineMap;
  private final DeadlineMap offlineDeadlineMap;
  private final long defaultMaxApiRequestSize;
  private final Map<String, Long> maxApiRequestSizeMap;
  private long maxNumBuffers;
  private long maxBufferSize;

  // TODO(schwardo): Rename this class to something less deadline-specific.
  private ApiDeadlineOracle(DeadlineMap deadlineMap, DeadlineMap offlineDeadlineMap,
                            long defaultMaxApiRequestSize, Map<String, Long> maxApiRequestSizeMap) {
    this.deadlineMap = deadlineMap;
    this.offlineDeadlineMap = offlineDeadlineMap;
    this.defaultMaxApiRequestSize = defaultMaxApiRequestSize;
    this.maxApiRequestSizeMap = maxApiRequestSizeMap;
  }

  public double getDeadline(String packageName, boolean isOffline, Number userDeadline) {
    if (isOffline) {
      return offlineDeadlineMap.getDeadline(packageName, userDeadline);
    } else {
      return deadlineMap.getDeadline(packageName, userDeadline);
    }
  }

  public long getMaxApiRequestSize(String packageName) {
    Long value = maxApiRequestSizeMap.get(packageName);
    if (value != null) {
      return value;
    }
    return defaultMaxApiRequestSize;
  }

  /**
   * Returns true if an API call to the specified package with the
   * specified size should use a shared buffer, or false otherwise.
   */
  public boolean shouldUseSharedBuffer(String packageName, boolean isOffline, long payloadSize) {
    if (maxNumBuffers < 1) {
      return false;
    }

    if (payloadSize > maxBufferSize) {
      return false;
    }

    long minContentSize = getMinContentSizeForBuffer(packageName, isOffline);
    long maxRequestSize = getMaxRequestSize(packageName, isOffline);
    if (payloadSize <= maxRequestSize && payloadSize >= minContentSize) {
      return true;
    }
    return false;
  }

  long getMinContentSizeForBuffer(String packageName, boolean isOffline) {
    if (isOffline) {
      return offlineDeadlineMap.getMinContentSizeForBuffer(packageName);
    } else {
      return deadlineMap.getMinContentSizeForBuffer(packageName);
    }
  }

  long getMaxRequestSize(String packageName, boolean isOffline) {
    if (isOffline) {
      return offlineDeadlineMap.getMaxRequestSize(packageName);
    } else {
      return deadlineMap.getMaxRequestSize(packageName);
    }
  }

  public void setMaxNumBuffers(long maxNumBuffers) {
    this.maxNumBuffers = maxNumBuffers;
  }

  public void setMaxBufferSize(long maxBufferSize) {
    this.maxBufferSize = maxBufferSize;
  }

  public void setDefaultMinContentSizeBuffer(long defaultMinContentSizeForBuffer) {
    offlineDeadlineMap.setDefaultMinContentSizeBuffer(defaultMinContentSizeForBuffer);
    deadlineMap.setDefaultMinContentSizeBuffer(defaultMinContentSizeForBuffer);
  }

  public void setDefaultMaxRequestSize(long defaultMaxRequestSize) {
    offlineDeadlineMap.setDefaultMaxRequestSize(defaultMaxRequestSize);
    deadlineMap.setDefaultMaxRequestSize(defaultMaxRequestSize);
  }

  public void addPackageDefaultDeadline(String packageName, double defaultDeadline) {
    deadlineMap.addDefaultDeadline(packageName, defaultDeadline);
  }

  public void addPackageMaxDeadline(String packageName, double maxDeadline) {
    deadlineMap.addMaxDeadline(packageName, maxDeadline);
  }

  public void addOfflinePackageDefaultDeadline(String packageName, double defaultDeadline) {
    offlineDeadlineMap.addDefaultDeadline(packageName, defaultDeadline);
  }

  public void addOfflinePackageMaxDeadline(String packageName, double maxDeadline) {
    offlineDeadlineMap.addMaxDeadline(packageName, maxDeadline);
  }

  public void addPackageMinContentSizeForBuffer(String packageName, long minContentSizeForBuffer) {
    deadlineMap.addMinContentSizeForBuffer(packageName, minContentSizeForBuffer);
  }

  public void addPackageMaxRequestSize(String packageName, long maxRequestSize) {
    deadlineMap.addMaxRequestSize(packageName, maxRequestSize);
  }

  public void addOfflinePackageMinContentSizeForBuffer(String packageName,
      long minContentSizeForBuffer) {
    offlineDeadlineMap.addMinContentSizeForBuffer(packageName, minContentSizeForBuffer);
  }

  public void addOfflinePackageMaxRequestSize(String packageName, long maxRequestSize) {
    offlineDeadlineMap.addMaxRequestSize(packageName, maxRequestSize);
  }

  public static class Builder {
    private DeadlineMap deadlineMap;
    private DeadlineMap offlineDeadlineMap;
    private long defaultMaxApiRequestSize;
    private Map<String, Long> maxApiRequestSizeMap;

    public Builder initDeadlineMap(double defaultDeadline, String defaultDeadlineMapString,
                                   double maxDeadline, String maxDeadlineMapString) {
      deadlineMap = new DeadlineMap(defaultDeadline, parseDoubleMap(defaultDeadlineMapString),
                                    maxDeadline, parseDoubleMap(maxDeadlineMapString));
      return this;
    }

    public Builder initOfflineDeadlineMap(double defaultDeadline, String defaultDeadlineMapString,
                                          double maxDeadline, String maxDeadlineMapString) {
      offlineDeadlineMap = new DeadlineMap(defaultDeadline,
                                           parseDoubleMap(defaultDeadlineMapString),
                                           maxDeadline,
                                           parseDoubleMap(maxDeadlineMapString));
      return this;
    }

    public Builder initMaxApiRequestSizeMap(long defaultMaxApiRequestSize,
                                            String maxApiRequestSizeMapString) {
      this.defaultMaxApiRequestSize = defaultMaxApiRequestSize;
      this.maxApiRequestSizeMap = parseLongMap(maxApiRequestSizeMapString);
      return this;
    }

    public ApiDeadlineOracle build() {
      if (deadlineMap == null || offlineDeadlineMap == null) {
        throw new IllegalStateException("All deadline maps must be initialized.");
      }
      return new ApiDeadlineOracle(deadlineMap, offlineDeadlineMap,
                                   defaultMaxApiRequestSize, maxApiRequestSizeMap);
    }

    private static Map<String, Double> parseDoubleMap(String mapString) {
      Map<String, Double> map = new HashMap<String, Double>();
      if (mapString.length() > 0) {
        for (String entry : mapString.split(",")) {
          int colon = entry.indexOf(':');
          if (colon == -1) {
            throw new IllegalArgumentException("Could not parse entry: " + entry);
          }
          map.put(entry.substring(0, colon), Double.parseDouble(entry.substring(colon + 1)));
        }
      }
      return map;
    }

    private static Map<String, Long> parseLongMap(String mapString) {
      Map<String, Long> map = new HashMap<String, Long>();
      if (mapString.length() > 0) {
        for (String entry : mapString.split(",")) {
          int colon = entry.indexOf(':');
          if (colon == -1) {
            throw new IllegalArgumentException("Could not parse entry: " + entry);
          }
          map.put(entry.substring(0, colon), Long.parseLong(entry.substring(colon + 1)));
        }
      }
      return map;
    }
  }

  private static class DeadlineMap {
    private final double defaultDeadline;
    private final Map<String, Double> defaultDeadlineMap;
    private final double maxDeadline;
    private final Map<String, Double> maxDeadlineMap;
    private final Map<String, Long> minContentSizeForBufferMap;
    private final Map<String, Long> maxRequestSizeMap;

    private long defaultMinContentSizeForBuffer;
    private long defaultMaxRequestSize;

    private DeadlineMap(double defaultDeadline,
                        Map<String, Double> defaultDeadlineMap,
                        double maxDeadline,
                        Map<String, Double> maxDeadlineMap) {
      this.defaultDeadline = defaultDeadline;
      this.defaultDeadlineMap = defaultDeadlineMap;
      this.maxDeadline = maxDeadline;
      this.maxDeadlineMap = maxDeadlineMap;
      this.minContentSizeForBufferMap = new HashMap<String, Long>();
      this.maxRequestSizeMap = new HashMap<String, Long>();
    }

    private void setDefaultMinContentSizeBuffer(long defaultMinContentSizeForBuffer) {
      this.defaultMinContentSizeForBuffer = defaultMinContentSizeForBuffer;
    }

    private void setDefaultMaxRequestSize(long defaultMaxRequestSize) {
      this.defaultMaxRequestSize = defaultMaxRequestSize;
    }

    private double getDeadline(String pkg, Number userDeadline) {
      double deadline;
      if (userDeadline == null) {
        // If the user didn't provide one, default it.
        deadline = getDoubleValue(pkg, defaultDeadlineMap, defaultDeadline);
      } else {
        deadline = userDeadline.doubleValue();
      }
      // Now cap it at the maximum deadline.
      return Math.min(deadline, getDoubleValue(pkg, maxDeadlineMap, maxDeadline));
    }

    private long getMinContentSizeForBuffer(String packageName) {
      return getLongValue(packageName, minContentSizeForBufferMap, defaultMinContentSizeForBuffer);
    }

    private long getMaxRequestSize(String packageName) {
      return getLongValue(packageName, maxRequestSizeMap, defaultMaxRequestSize);
    }

    /**
     * Adds new deadlines for the specified package.  If the package was
     * already known (either from a previous {@code addPackage} call or
     * from the string passed into the constructor, these values will
     * override it.
     */
    private void addDefaultDeadline(String packageName, double defaultDeadline) {
      defaultDeadlineMap.put(packageName, defaultDeadline);
    }

    private void addMaxDeadline(String packageName, double maxDeadline) {
      maxDeadlineMap.put(packageName, maxDeadline);
    }

    private void addMinContentSizeForBuffer(String packageName, long minContentSizeForBuffer) {
      minContentSizeForBufferMap.put(packageName, minContentSizeForBuffer);
    }

    private void addMaxRequestSize(String packageName, long maxRequestSize) {
      maxRequestSizeMap.put(packageName, maxRequestSize);
    }

    private double getDoubleValue(String packageName, Map<String, Double> map, double fallthrough) {
      Double value = map.get(packageName);
      if (value == null) {
        value = fallthrough;
      }
      return value;
    }

    private long getLongValue(String packageName, Map<String, Long> map, long fallthrough) {
      Long value = map.get(packageName);
      if (value == null) {
        value = fallthrough;
      }
      return value;
    }
  }
}
