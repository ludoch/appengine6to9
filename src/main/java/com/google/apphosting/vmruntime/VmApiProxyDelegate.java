// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.apphosting.vmruntime;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.ApiConfig;
import com.google.apphosting.api.ApiProxy.ApiProxyException;
import com.google.apphosting.api.ApiProxy.LogRecord;
import com.google.apphosting.api.ApiProxy.RPCFailedException;
import com.google.apphosting.utils.remoteapi.RemoteApiPb;
import com.google.common.collect.Lists;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delegates AppEngine API calls to a local http API proxy when running inside a VM.
 *
 * <p>Instances should be registered using ApiProxy.setDelegate(ApiProxy.Delegate).
 *
 */
public class VmApiProxyDelegate implements ApiProxy.Delegate<VmApiProxyEnvironment> {

  private static final Logger logger = Logger.getLogger(VmApiProxyDelegate.class.getName());

  public static final String RPC_DEADLINE_HEADER = "X-Google-RPC-Service-Deadline";
  public static final String RPC_STUB_ID_HEADER = "X-Google-RPC-Service-Endpoint";
  public static final String RPC_METHOD_HEADER = "X-Google-RPC-Service-Method";

  public static final String REQUEST_ENDPOINT = "/rpc_http";
  public static final String REQUEST_STUB_ID = "app-engine-apis";
  public static final String REQUEST_STUB_METHOD = "/VMRemoteAPI.CallRemoteAPI";

  // This is the same definition as com.google.apphosting.api.API_DEADLINE_KEY. It is also defined
  // here to avoid being exposed to the users in appengine-api.jar.
  protected static final String API_DEADLINE_KEY =
      "com.google.apphosting.api.ApiProxy.api_deadline_key";

  protected int defaultTimeoutMs;
  protected final ExecutorService executor;

  public VmApiProxyDelegate() {
    defaultTimeoutMs = 5 * 60 * 1000;
    // TODO(avk): explore using an HTTP client with NIO2 support.
    executor = Executors.newCachedThreadPool();
  }

  /**
   * Opens a connection to the ApiProxy server specified in the environment.
   *
   * <p>May be overridden in tests.
   *
   * @param environment used to identify the ApiProxy server host and port.
   * @return the HTTP URL connection created.
   * @throws IOException if a connection to the server cannot be established.
   * @throws MalformedURLException if the environment specified server is invalid.
   */
  protected HttpURLConnection openConnection(VmApiProxyEnvironment environment)
    throws IOException, MalformedURLException {
    URL url = new URL("http://" + environment.getServer() + REQUEST_ENDPOINT);
    return (HttpURLConnection) url.openConnection();
  }

  @Override
  public byte[] makeSyncCall(
        VmApiProxyEnvironment environment,
        String packageName,
        String methodName,
        byte[] requestData)
      throws ApiProxyException {
    return makeSyncCallWithTimeout(environment, packageName, methodName, requestData,
        defaultTimeoutMs);
  }

  /**
   * It is different from {@link #makeSyncCall(VmApiProxyEnvironment, String, String, byte[])} in
   * just one parameter, which is timeoutMs.
   *
   * @param timeoutMs this is the time out for the Stubby endpoint (i.e. REQUEST_ENDPOINT). The time
   *        out for the requested URL should be set in the environment variable. See
   *        {@link #makeSyncCall(VmApiProxyEnvironment, String, String, byte[])}
   *
   * @throws ApiProxyException
   */
  private byte[] makeSyncCallWithTimeout(
          VmApiProxyEnvironment environment,
          String packageName,
          String methodName,
          byte[] requestData,
          int timeoutMs)
        throws ApiProxyException {
    return makeApiCall(environment, packageName, methodName, requestData, timeoutMs, false);
  }

  private byte[] makeApiCall(VmApiProxyEnvironment environment,
      String packageName,
      String methodName, byte[] requestData, int timeoutMs, boolean wasAsync) {
    // If this was caused by an async call we need to return the pending call semaphore.
    environment.apiCallStarted(VmRuntimeUtils.MAX_USER_API_CALL_WAIT_MS, wasAsync);
    try {
      return runSyncCall(environment, packageName, methodName, requestData, timeoutMs);
    } finally {
      environment.apiCallCompleted();
    }
  }

  // @VisibleForTesting(productionVisibility = Visibility.PROTECTED)
  protected byte[] runSyncCall(VmApiProxyEnvironment environment, String packageName,
      String methodName, byte[] requestData, int timeoutMs) {
    RemoteApiPb.Request remoteRequest = new RemoteApiPb.Request();
    remoteRequest.setServiceName(packageName);
    remoteRequest.setMethod(methodName);
    remoteRequest.setRequestId(environment.getTicket());
    remoteRequest.setRequestAsBytes(requestData);
    byte[] remoteRequestData = remoteRequest.toByteArray();
    HttpURLConnection connection = null;
    try {
      connection = openConnection(environment);
      connection.setConnectTimeout(timeoutMs);
      connection.setReadTimeout(timeoutMs);
      // TODO(avk): POST may hang since the socket does not support setWriteTimeout().
      connection.setDoInput(true);
      connection.setDoOutput(true);
      connection.setUseCaches(false);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/octet-stream");
      connection.setRequestProperty("Content-Length", Integer.toString(remoteRequestData.length));
      connection.setRequestProperty(RPC_STUB_ID_HEADER, REQUEST_STUB_ID);
      connection.setRequestProperty(RPC_METHOD_HEADER, REQUEST_STUB_METHOD);

      Double deadline = (Double) (environment.getAttributes().get(API_DEADLINE_KEY));
      if (deadline == null) {
        connection.setRequestProperty(RPC_DEADLINE_HEADER,
            Double.toString(TimeUnit.SECONDS.convert(timeoutMs, TimeUnit.MILLISECONDS)));
      } else  {
        connection.setRequestProperty(RPC_DEADLINE_HEADER, Double.toString(deadline));
      }
      // If the incoming request has a dapper trace header: set it on outgoing API calls
      // so they are tied to the original request.
      Object dapperHeader = environment.getAttributes()
          .get(VmApiProxyEnvironment.AttributeMapping.DAPPER_ID.attributeKey);
      if (dapperHeader instanceof String) {
        connection.setRequestProperty(
            VmApiProxyEnvironment.AttributeMapping.DAPPER_ID.headerKey, (String) dapperHeader);
      }
    } catch (IOException e) {
      if (connection != null) {
        connection.disconnect();
      }
      logger.log(Level.WARNING, "ApiProxy " + packageName + "." + methodName + ": " +
          e.getMessage(), e);
      throw new RPCFailedException(packageName, methodName);
    }

    BufferedOutputStream bos = null;
    BufferedInputStream bis = null;
    BufferedInputStream errorStream = null;
    Scanner errorStreamScanner = null;
    RemoteApiPb.Response remoteResponse = new RemoteApiPb.Response();
    try {
      bos = new BufferedOutputStream(connection.getOutputStream());
      bos.write(remoteRequestData);
      bos.close();

      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        logger.info("HTTP ApiProxy rejected " + packageName + "." + methodName + " with error code "
            + connection.getResponseCode());
        errorStream = new BufferedInputStream(connection.getErrorStream());
        errorStreamScanner = new Scanner(errorStream).useDelimiter("\\Z");
        logger.info("Error body: " + errorStreamScanner.next());
        errorStreamScanner.close();
        throw new RPCFailedException(packageName, methodName);
      }
      bis = new BufferedInputStream(connection.getInputStream());
      if (!remoteResponse.parseFrom(bis)) {
        logger.info("HTTP ApiProxy unable to parse response for " + packageName + "." + methodName);
        throw new RPCFailedException(packageName, methodName);
      }
    } catch (IOException e) {
      logger.info("HTTP ApiProxy I/O error for " + packageName + "." + methodName +
          ": " + e.getMessage());
      throw new RPCFailedException(packageName, methodName);
    } finally {
      if (bos != null) {
        try {
          bos.close();
        } catch (IOException e) {
          logger.fine(e.getMessage());
        }
      }
      if (bis != null) {
        try {
          bis.close();
        } catch (IOException e) {
          logger.fine(e.getMessage());
        }
      }
      if (errorStreamScanner != null) {
        errorStreamScanner.close();
      }
      if (errorStream != null) {
        try {
          errorStream.close();
        } catch (IOException e) {
          logger.fine(e.getMessage());
        }
      }
      connection.disconnect();
    }

    if (remoteResponse.hasRpcError() || remoteResponse.hasApplicationError()) {
      throw convertRemoteError(
          remoteResponse, packageName, methodName, logger);
    }
    return remoteResponse.getResponseAsBytes();
  }

  /**
   * Convert RemoteApiPb.Response errors to the appropriate exception.
   *
   * <p>The response must have exactly one of the RpcError and ApplicationError fields set.
   *
   * @param remoteResponse the Response
   * @param packageName the name of the API package.
   * @param methodName the name of the method within the API package.
   * @param logger the Logger used to create log messages.
   * @return ApiProxyException
   */
  private static ApiProxyException convertRemoteError(RemoteApiPb.Response remoteResponse,
      String packageName, String methodName, Logger logger) {
    if (remoteResponse.hasRpcError()) {
      return convertApiResponseRpcErrorToException(
            remoteResponse.getRpcError(),
            packageName,
            methodName,
            logger);
    }

    // Otherwise it's an application error
    RemoteApiPb.ApplicationError error = remoteResponse.getApplicationError();
    return new ApiProxy.ApplicationException(error.getCode(), error.getDetail());
  }

  /**
   * Convert the RemoteApiPb.RpcError to the appropriate exception.
   *
   * @param rpcError the RemoteApiPb.RpcError.
   * @param packageName the name of the API package.
   * @param methodName the name of the method within the API package.
   * @param logger the Logger used to create log messages.
   * @return ApiProxyException
   */
  private static ApiProxyException convertApiResponseRpcErrorToException(
      RemoteApiPb.RpcError rpcError, String packageName, String methodName, Logger logger) {

    int rpcCode = rpcError.getCode();
    String errorDetail = rpcError.getDetail();
    if (rpcCode > RemoteApiPb.RpcError.ErrorCode.values().length) {
      logger.severe("Received unrecognized error code from server: " + rpcError.getCode() +
          " details: " + errorDetail);
      return new ApiProxy.UnknownException(packageName, methodName);
    }
    RemoteApiPb.RpcError.ErrorCode errorCode = RemoteApiPb.RpcError.ErrorCode.values()[
        rpcError.getCode()];
    logger.warning("RPC failed : " + errorCode + " : " + errorDetail);

    // This is very similar to apphosting/utils/runtime/ApiProxyUtils.java#convertApiError,
    // which is for APIResponse. TODO(apphosting): retire both in favor of gRPC.
    switch (errorCode) {
      case CALL_NOT_FOUND:
        return new ApiProxy.CallNotFoundException(packageName, methodName);
      case PARSE_ERROR:
        return new ApiProxy.ArgumentException(packageName, methodName);
      case SECURITY_VIOLATION:
        logger.severe("Security violation: invalid request id used!");
        return new ApiProxy.UnknownException(packageName, methodName);
      case CAPABILITY_DISABLED:
        return new ApiProxy.CapabilityDisabledException(
            errorDetail, packageName, methodName);
      case OVER_QUOTA:
        return new ApiProxy.OverQuotaException(packageName, methodName);
      case REQUEST_TOO_LARGE:
        return new ApiProxy.RequestTooLargeException(packageName, methodName);
      case RESPONSE_TOO_LARGE:
        return new ApiProxy.ResponseTooLargeException(packageName, methodName);
      case BAD_REQUEST:
        return new ApiProxy.ArgumentException(packageName, methodName);
      case CANCELLED:
        return new ApiProxy.CancelledException(packageName, methodName);
      case FEATURE_DISABLED:
        return new ApiProxy.FeatureNotEnabledException(
            errorDetail, packageName, methodName);
      case DEADLINE_EXCEEDED:
        return new ApiProxy.ApiDeadlineExceededException(packageName, methodName);
      default:
        return new ApiProxy.UnknownException(packageName, methodName);
    }
  }

  private class MakeSyncCall implements Callable<byte[]> {
    private final VmApiProxyDelegate delegate;
    private final VmApiProxyEnvironment environment;
    private final String packageName;
    private final String methodName;
    private final byte[] requestData;
    private final int timeoutMs;

    public MakeSyncCall(VmApiProxyDelegate delegate,
        VmApiProxyEnvironment environment,
        String packageName,
        String methodName,
        byte[] requestData,
        int timeoutMs) {
      this.delegate = delegate;
      this.environment = environment;
      this.packageName = packageName;
      this.methodName = methodName;
      this.requestData = requestData;
      this.timeoutMs = timeoutMs;
    }

    @Override
    public byte[] call() throws Exception {
      return delegate.makeApiCall(environment,
          packageName,
          methodName,
          requestData,
          timeoutMs,
          true);
    }
  }

  @Override
  public Future<byte[]> makeAsyncCall(
        VmApiProxyEnvironment environment,
        String packageName,
        String methodName,
        byte[] request,
        ApiConfig apiConfig) {
    int timeoutMs = defaultTimeoutMs;
    if (apiConfig != null && apiConfig.getDeadlineInSeconds() != null) {
      timeoutMs = (int) (apiConfig.getDeadlineInSeconds() * 1000);
    }
    environment.aSyncApiCallAdded(VmRuntimeUtils.MAX_USER_API_CALL_WAIT_MS);
    return executor.submit(new MakeSyncCall(this, environment, packageName,
        methodName, request, timeoutMs));
  }

  @Override
  public void log(VmApiProxyEnvironment environment, LogRecord record) {
    if (environment != null) {
      environment.addLogRecord(record);
    }
  }

  @Override
  public void flushLogs(VmApiProxyEnvironment environment) {
    if (environment != null) {
      environment.flushLogs();
    }
  }

  @Override
  public List<Thread> getRequestThreads(VmApiProxyEnvironment environment) {
    Object threadFactory =
        environment.getAttributes().get(VmApiProxyEnvironment.REQUEST_THREAD_FACTORY_ATTR);
    if (threadFactory != null && threadFactory instanceof VmRequestThreadFactory) {
      return ((VmRequestThreadFactory) threadFactory).getRequestThreads();
    }
    logger.warning("Got a call to getRequestThreads() but no VmRequestThreadFactory is available");
    return Lists.newLinkedList();
  }
}
