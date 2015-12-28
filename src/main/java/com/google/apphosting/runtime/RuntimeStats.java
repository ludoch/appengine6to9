
package com.google.apphosting.runtime;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;


public class RuntimeStats {

  // NB(tobyr) Consider using some datastructure/algorithm that gives better
  // performance for storing statistical samples of top keys if performance
  // becomes a concern. schwardo suggests something like
  // http://s/?fileprint=//depot/google3/stats/util/sampling-table-inl.h

  private static final Logger logger = Logger.getLogger(RuntimeStats.class.getName());

  private static final int DETAIL_CAP = 5;

  private static volatile boolean enabled;

  private static volatile LogLevel logLevel = LogLevel.CAPPED_DETAIL;

  // TODO(tobyr) RuntimeStats are not thread-safe, but rather are collected
  // per-thread. When we allow user-code to run in multiple threads, we'll
  // need to merge RuntimeStats from all threads at the end of each request.
  private static ThreadLocal<RuntimeStats> stats = new ThreadLocal<RuntimeStats>() {
    @Override
    protected RuntimeStats initialValue() {
      return new RuntimeStats();
    }
  };

  /**
   * Sorts according to total calls, in reverse order (highest number of calls
   * sorted lowest).
   */
  private static final Comparator<OperationCount> operationsComparator
      = new Comparator<OperationCount>() {
    public int compare(OperationCount oc1, OperationCount oc2) {
      return (oc2.totalFailed + oc2.totalSucceeded) - (oc1.totalFailed + oc1.totalSucceeded);
    }
  };

  /**
   * Sorts according to successful calls, in reverse order (highest number of calls
   * sorted lowest).
   */
  private static final Comparator<Map.Entry<Key, Stats>> statsSuccessfulComparator
      = new Comparator<Map.Entry<Key, Stats>>() {
    public int compare(Map.Entry<Key, Stats> e1, Map.Entry<Key, Stats> e2) {
      Stats s1 = e1.getValue();
      Stats s2 = e2.getValue();
      return s2.numSuccessful - s1.numSuccessful;
    }
  };

  /**
   * Sorts according to failed calls, in reverse order (highest number of calls
   * sorted lowest).
   */
  private static final Comparator<Map.Entry<Key, Stats>> statsFailedComparator
      = new Comparator<Map.Entry<Key, Stats>>() {
    public int compare(Map.Entry<Key, Stats> e1, Map.Entry<Key, Stats> e2) {
      Stats s1 = e1.getValue();
      Stats s2 = e2.getValue();
      return s2.numFailed - s1.numFailed;
    }
  };

  private Map<String,Map<Key,Stats>> operations = new HashMap<String,Map<Key,Stats>>();

  public enum LogLevel {

    /**
     * Displays a single summary line for each collected statistic.
     * A couple of K worth of logging per request.
     */
    SUMMARY_ONLY,

    /**
     * Displays the summary plus a capped number of detail lines.
     * Some 10K's worth of logging per request.
     */
    CAPPED_DETAIL,

    /**
     * Displays the summary plus all details. Could create 1M+ of
     * logging per request. Recommended for development only.
     */
    FULL_DETAIL;
  }

  static class Key {
    List<Object> values;
    private int hashCode;
    Key(List<Object> values) {
      this.values = values;
      this.hashCode = values.hashCode();
    }
    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Key)) {
        return false;
      }
      Key p = (Key)obj;
      if (values == null) {
        return p.values == null;
      }
      return values.equals(p.values);
    }
    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  static class Stats {
    int numSuccessful;
    int numFailed;
  }

  enum ResultType {
    Success {
      public int getCount(Stats stats) {
        return stats.numSuccessful;
      }
      public Comparator<Map.Entry<Key, Stats>> getComparator() {
        return statsSuccessfulComparator;
      }
    },
    Failure {
      public int getCount(Stats stats) {
        return stats.numFailed;
      }
      public Comparator<Map.Entry<Key, Stats>> getComparator() {
        return statsFailedComparator;
      }
    };

    public abstract int getCount(Stats stats);
    public abstract Comparator<Map.Entry<Key, Stats>> getComparator();
  };

  /**
   * Initialized via the {@link #stats} ThreadLocal.
   */
  private RuntimeStats() {
  }

  /**
   * Returns the stats for the currently executing thread.
   */
  public static RuntimeStats getThreadLocalStats() {
    return stats.get();
  }

  public static void setEnabled(boolean enabled) {
    RuntimeStats.enabled = enabled;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  public static LogLevel getLogLevel() {
    return logLevel;
  }

  public static void setLogLevel(LogLevel logLevel) {
    RuntimeStats.logLevel = logLevel;
  }

  private static class OperationCount {
    OperationCount(String operation, int succeeded, int failed) {
      this.operation = operation;
      this.totalSucceeded = succeeded;
      this.totalFailed = failed;
    }
    String operation;
    int totalSucceeded;
    int totalFailed;
  }

  public void logStats() {
    if (!enabled) {
      return;
    }

    List<OperationCount> operationCounts = new ArrayList<OperationCount>(operations.size());

    for (String operation : operations.keySet()) {
      int totalSucceeded = 0;
      int totalFailed = 0;

      Map<Key,Stats> statsByKey = operations.get(operation);
      for (Map.Entry<Key,Stats> entry : statsByKey.entrySet()) {
        Stats stats = entry.getValue();
        totalSucceeded += stats.numSuccessful;
        totalFailed += stats.numFailed;
      }

      operationCounts.add(new OperationCount(operation, totalSucceeded, totalFailed));
    }

    Collections.sort(operationCounts, operationsComparator);

    for (OperationCount opCount : operationCounts) {
      logger.log(Level.INFO, opCount.operation + " - Total succeeded: " + opCount.totalSucceeded
          + " Total failed: " + opCount.totalFailed);

      Map<Key,Stats> statsByKey = operations.get(opCount.operation);
      Set<Map.Entry<Key,Stats>> statsEntries = statsByKey.entrySet();
      int size = statsEntries.size();
      Map.Entry<Key,Stats>[] entries = statsEntries.toArray(newArray(size));

      if (logLevel.ordinal() > LogLevel.SUMMARY_ONLY.ordinal()) {
        printStats(entries, ResultType.Failure);
        printStats(entries, ResultType.Success);
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  private Map.Entry<Key,Stats>[] newArray(int size) {
    return new Map.Entry[size];
  }

  private void printStats(Map.Entry<Key,Stats>[] entries, ResultType type) {
    final int maxEntriesPerLogLine = 20;
    // NB(tobyr) Keep this well under max logline size in runtime.cc (8K)
    final int approximateMaxLogSize = 300 * maxEntriesPerLogLine;
    StringBuffer logLine = new StringBuffer(approximateMaxLogSize);
    Comparator<Map.Entry<Key, Stats>> comparator = type.getComparator();
    Arrays.sort(entries, comparator);

    int entriesLogged = 0;
    int entriesBuffered = 0;

    for (Map.Entry<Key,Stats> entry : entries) {
      if (entriesBuffered > maxEntriesPerLogLine) {
        logger.log(Level.INFO, logLine.toString());
        logLine.setLength(0);
        entriesBuffered = 0;
      }

      if (logLevel == LogLevel.CAPPED_DETAIL) {
        if (entriesLogged == RuntimeStats.DETAIL_CAP) {
          logLine.append("[" + (entries.length - entriesLogged) + " unlogged entries...]");
          break;
        }
      }
      
      Key key = entry.getKey();
      Stats stats = entry.getValue();
      if (type.getCount(stats) == 0) {
        break;
      }
      String msg = "(" + key.values + ") " + type + ": " + type.getCount(stats) + "\n";
      logLine.append(msg);
      ++entriesLogged;
      ++entriesBuffered;
    }

    if (logLine.length() > 0) {
      logger.info(logLine.toString());
    }
  }

  /**
   * Releases objects pinned to the stats collection.
   * <strong>This must be called at the end of each request to ensure
   * that application values can be GC'd.</strong>.
   */
  public void clear() {
    operations.clear();
  }

  /**
   * Records that the operation succesfully took place.
   * Also records what parameters were supplied to the operation.
   * The parameters are used as a key to group together statistics recorded
   * for an operation.
   * <p>
   * <strong>BE CAREFUL! Any values supplied to params will be pinned for the
   * duration of the request and will not be GC'd. In order to avoid chewing
   * up a large amount of memory, you should be careful to select keys that
   * are unlikely to be large or vary per call for operations which may
   * have large call counts. For example, record the method signature
   * per method invoke rather than the actual arguments for each invoke.
   * </strong>
   */
  public void recordSuccess(String operation, Object... params) {
    record(operation, true, params);
  }

  /**
   * Records that the operation failed.
   * Also records what parameters were supplied to the operation. 
   * The parameters are used as a key to group together statistics recorded
   * for an operation.
   * <p>
   * <strong>BE CAREFUL! Any values supplied to params will be pinned for the
   * duration of the request and will not be GC'd. In order to avoid chewing
   * up a large amount of memory, you should be careful to select keys that
   * are unlikely to be large or vary per call for operations which may
   * have large call counts. For example, record the method signature
   * per method invoke rather than the actual arguments for each invoke.
   * </strong>
   */
  public void recordFailure(String operation, Object... params) {
    record(operation, false, params);
  }

  private void record(String operation, boolean succeeded, Object... params) {
    if (!enabled) {
      return;
    }

    Key key = new Key(Arrays.asList(params));
    Map<Key,Stats> statsByKey = operations.get(operation);

    if (statsByKey == null) {
      statsByKey = new HashMap<Key,Stats>();
      operations.put(operation, statsByKey);
    }
    Stats stats = statsByKey.get(key);
    if (stats == null) {
      stats = new Stats();
      statsByKey.put(key, stats);
    }
    if (succeeded) {
      stats.numSuccessful++;
    } else {
      stats.numFailed++;
    }
  }
}
