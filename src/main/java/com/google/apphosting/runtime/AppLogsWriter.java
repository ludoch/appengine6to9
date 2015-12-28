
package com.google.apphosting.runtime;

import com.google.apphosting.api.ApiProxy.LogRecord;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.logservice.LogServicePb.FlushRequest;
import com.google.apphosting.base.AppLogsPb.AppLogLine;
import com.google.apphosting.base.AppLogsPb.AppLogGroup;
import com.google.apphosting.base.RuntimePb.UPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AppLogsWriter {
  private static final Logger logger =
      Logger.getLogger(AppLogsWriter.class.getName());

  // (Some constants below package scope for testability)
  static final String LOG_CONTINUATION_SUFFIX = "\n<continued in next message>";
  static final int LOG_CONTINUATION_SUFFIX_LENGTH = LOG_CONTINUATION_SUFFIX.length();
  static final String LOG_CONTINUATION_PREFIX = "<continued from previous message>\n";
  static final int LOG_CONTINUATION_PREFIX_LENGTH = LOG_CONTINUATION_PREFIX.length();
  static final int MIN_MAX_LOG_MESSAGE_LENGTH = 1024;

  private final int maxLogMessageLength;
  private final int logCutLength;
  private final int logCutLengthDiv10;
  private final UPResponse upResponse;
  private final long maxBytesToFlush;
  private long currentByteCount;
  private final int maxSecondsBetweenFlush;
  private Future<byte[]> currentFlush;
  private Stopwatch stopwatch;

  /**
   * Construct an AppLogsWriter instance.
   *
   * @param upResponse The protobuf response instance that holds the return
   *   value for EvaluationRuntime.HandleRequest. This is used to return
   *   any logs that were not sent to the appserver with an intermediate flush
   *   when the request ends.
   * @param maxBytesToFlush The maximum number of bytes of log message to
   *   allow in a single flush. The code flushes any cached logs before
   *   reaching this limit. If this is 0, AppLogsWriter will not start
   *   an intermediate flush based on size.
   * @param maxLogMessageLength The maximum length of an individual log line.
   *   A single log line longer than this will be written as multiple log
   *   entries (with the continuation prefix/suffixes added to indicate this).
   * @param maxFlushSeconds The amount of time to allow a log line to sit
   *   cached before flushing. Once a log line has been sitting for more
   *   than the specified time, all currently cached logs are flushed. If
   *   this is 0, no time based flushing occurs.
   *   N.B. because we only check the time on a log call, it is possible for
   *   a log to stay cached long after the specified time has been reached.
   *   Consider this example (assume maxFlushSeconds=60): the app logs a message
   *   when the handler starts but then does not log another message for 10
   *   minutes. The initial log will stay cached until the second message
   *   is logged.
   */
  public AppLogsWriter(UPResponse upResponse, long maxBytesToFlush,
      int maxLogMessageLength, int maxFlushSeconds) {
    this.upResponse = upResponse;
    this.maxSecondsBetweenFlush = maxFlushSeconds;

    if (maxLogMessageLength < MIN_MAX_LOG_MESSAGE_LENGTH) {
      String message = String.format(
          "maxLogMessageLength sillily small (%s); setting maxLogMessageLength to %s",
          maxLogMessageLength, MIN_MAX_LOG_MESSAGE_LENGTH);
      logger.warning(message);
      this.maxLogMessageLength = MIN_MAX_LOG_MESSAGE_LENGTH;
    } else {
      this.maxLogMessageLength = maxLogMessageLength;
    }
    logCutLength = maxLogMessageLength - LOG_CONTINUATION_SUFFIX_LENGTH;
    logCutLengthDiv10 = logCutLength / 10;

    // This should never happen, but putting here just in case.
    if (maxBytesToFlush < this.maxLogMessageLength) {
      String message = String.format(
          "maxBytesToFlush (%s) smaller than  maxLogMessageLength (%s)",
          maxBytesToFlush, this.maxLogMessageLength);
      logger.warning(message);
      this.maxBytesToFlush = this.maxLogMessageLength;
    } else {
      this.maxBytesToFlush = maxBytesToFlush;
    }

    // Always have a stopwatch even if we're not doing time based flushing
    // to keep code a bit simpler
    stopwatch = Stopwatch.createUnstarted();
  }

  /**
   * Add the specified {@link LogRecord} for the current request.  If
   * enough space (or in the future, time) has accumulated, an
   * asynchronous flush may be started.  If flushes are backed up,
   * this method may block.
   */
  public void addLogRecordAndMaybeFlush(LogRecord fullRecord) {
    for (LogRecord record : split(fullRecord)){
      AppLogLine logLine = new AppLogLine();
      logLine.setLevel(record.getLevel().ordinal());
      logLine.setTimestampUsec(record.getTimestamp());
      logLine.setMessage(record.getMessage());
      // Use maxEncodingSize() here because it's faster and accurate
      // enough for us.  It uses the maximum possible size for varint
      // values, but the real size of strings.
      int maxEncodingSize = logLine.maxEncodingSize();
      if (maxBytesToFlush > 0 &&
          (currentByteCount + maxEncodingSize) > maxBytesToFlush) {
        logger.info(currentByteCount + " bytes of app logs pending, starting flush...");
        waitForCurrentFlushAndStartNewFlush();
      }
      if (upResponse.appLogSize() == 0) {
        // We only want to flush once a log message has been around for
        // longer than maxSecondsBetweenFlush. So, we only start the timer
        // when we add the first message so we don't include time when
        // the queue is empty.
        stopwatch.start();
      }
      upResponse.addAppLog(logLine);
      currentByteCount += maxEncodingSize;
    }

    if (maxSecondsBetweenFlush > 0 &&
        stopwatch.elapsed(TimeUnit.SECONDS) >= maxSecondsBetweenFlush) {
      waitForCurrentFlushAndStartNewFlush();
    }
  }

  /**
   * Starts an asynchronous flush.  This method may block if flushes
   * are backed up.
   */
  private void waitForCurrentFlushAndStartNewFlush() {
    waitForCurrentFlush();
    if (upResponse.appLogSize() > 0) {
      currentFlush = doFlush();
    }
  }

  /**
   * Initiates a synchronous flush.  This method will always block
   * until any pending flushes and its own flush completes.
   */
  public void flushAndWait() {
    waitForCurrentFlush();
    if (upResponse.appLogSize() > 0) {
      currentFlush = doFlush();
      waitForCurrentFlush();
    }
  }

  /**
   * This method blocks until any outstanding flush is completed. This method
   * should be called prior to {@link #doFlush()} so that it is impossible for
   * the appserver to process logs out of order.
   */
  private void waitForCurrentFlush() {
    if (currentFlush != null) {
      logger.info("Previous flush has not yet completed, blocking.");
      try {
        currentFlush.get();
      } catch (InterruptedException ex) {
        logger.warning("Interruped while blocking on a log flush, setting interrupt bit and " +
                       "continuing.  Some logs may be lost or occur out of order!");
        Thread.currentThread().interrupt();
      } catch (ExecutionException ex) {
        logger.log(
            Level.WARNING,
            "A log flush request failed.  Log messages may have been lost!", ex);
      }
      currentFlush = null;
    }
  }

  private Future<byte[]> doFlush() {
    AppLogGroup group = new AppLogGroup();
    for (AppLogLine logLine : upResponse.appLogs()) {
      group.addLogLine(logLine);
    }
    upResponse.clearAppLog();
    currentByteCount = 0;
    stopwatch.reset();
    FlushRequest request = new FlushRequest();
    request.setLogsAsBytes(group.toByteArray());
    // This assumes that we are always doing a flush from the request
    // thread.  See the TODO above.
    return ApiProxy.makeAsyncCall("logservice", "Flush",
                                  request.toByteArray());
  }

  /**
   * Because the App Server will truncate log messages that are too
   * long, we want to split long log messages into mutliple messages.
   * This method returns a {@link List} of {@code LogRecord}s, each of
   * which have the same {@link LogRecord#getLevel()} and
   * {@link LogRecord#getTimestamp()} as
   * this one, and whose {@link LogRecord#getMessage()} is short enough
   * that it will not be truncated by the App Server. If the
   * {@code message} of this {@code LogRecord} is short enough, the list
   * will contain only this  {@code LogRecord}. Otherwise the list will
   * contain multiple {@code LogRecord}s each of which contain a portion
   * of the {@code message}. Additionally, strings will be
   * prepended and appended to each of the {@code message}s indicating
   * that the message is continued in the following log message or is a
   * continuation of the previous log mesage.
   */
  @VisibleForTesting
  List<LogRecord> split(LogRecord aRecord){
    // This method is public so it is testable.
    LinkedList<LogRecord> theList = new LinkedList<LogRecord>();
    String message = aRecord.getMessage();
    if (null == message || message.length() <= maxLogMessageLength){
      theList.add(aRecord);
      return theList;
    }
    String remaining = message;
    while (remaining.length() > 0){
      String nextMessage;
      if (remaining.length() <= maxLogMessageLength){
        nextMessage = remaining;
        remaining = "";
      } else {
        int cutLength = logCutLength;
        boolean cutAtNewline = false;
        // Try to cut the string at a friendly point
        int friendlyCutLength = remaining.lastIndexOf('\n', logCutLength);
        // But only if that yields a message of reasonable length
        if (friendlyCutLength > logCutLengthDiv10){
          cutLength = friendlyCutLength;
          cutAtNewline = true;
        }
        nextMessage = remaining.substring(0, cutLength) + LOG_CONTINUATION_SUFFIX;
        remaining = remaining.substring(cutLength + (cutAtNewline ? 1 : 0));
        // Only prepend the continuation prefix if doing so would not push
        // the length of the next message over the limit.
        if (remaining.length() > maxLogMessageLength ||
            remaining.length() + LOG_CONTINUATION_PREFIX_LENGTH <= maxLogMessageLength){
          remaining = LOG_CONTINUATION_PREFIX + remaining;
        }
      }
      theList.add(new LogRecord(aRecord, nextMessage));
    }
    return theList;
  }

  /**
   * Sets the stopwatch used for time based flushing.
   *
   * This method is not simply visible for testing, it only exists for testing.
   *
   * @param stopwatch The {@link Stopwatch} instance to use.
   */
  @VisibleForTesting
  void setStopwatch(Stopwatch stopwatch) {
    this.stopwatch = stopwatch;
  }

  /**
   * Get the max length of an individual log message.
   *
   * This method is not simply visible for testing, it only exists for testing.
   */
  @VisibleForTesting
  int getMaxLogMessageLength() {
    return maxLogMessageLength;
  }

  /**
   * Get the maximum number of log bytes that can be sent at a single time.
   *
   * This code is not simply visible for testing, it only exists for testing.
   */
  @VisibleForTesting
  long getByteCountBeforeFlushing() {
    return maxBytesToFlush;
  }
}
