
package com.google.apphosting.runtime;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.List;

/**
 * {@EnvironmentFactory} creates instances of {@link ApiProxy.Environment}.
 *
 */
public interface EnvironmentFactory {
  ApiProxy.Environment createEnvironment(AppVersion appVersion, UPRequest upRequest,
                                         UPResponse upResponse, CpuRatioTimer requestTimer,
                                         String requestId, List<Future<?>> asyncFutures,
                                         Semaphore outstandingApiRpcSemaphore,
                                         ThreadGroup requestThreadGroup, RequestState state,
                                         Long millisUntilSoftDeadline);
}
