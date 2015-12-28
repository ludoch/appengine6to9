
package com.google.apphosting.runtime;

import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Map;
import javax.servlet.ServletException;

/**
 * {@code CompositeServletEngineAdapter} wraps around one or more
 * {@link ServletEngineAdapter} instances, each of which has a
 * registered handler type.  Requests to add or delete {@link
 * AppVersion} instances are forwarded on to each {@link
 * ServletEngineAdapter}.  {@link #serviceRequest} calls are sent to
 * the {@link ServletEngineAdapter} that is registered for the
 * specified handler type.
 *
 */
public class CompositeServletEngineAdapter implements ServletEngineAdapter {
  private final Map<Integer, ServletEngineAdapter> adapterMap;

  public CompositeServletEngineAdapter(Map<Integer, ServletEngineAdapter> adapterMap) {
    this.adapterMap = adapterMap;
  }

  @Override
  public void start(String serverInfo) {
    for (ServletEngineAdapter adapter : adapterMap.values()) {
      adapter.start(serverInfo);
    }
  }

  @Override
  public void stop() {
    for (ServletEngineAdapter adapter : adapterMap.values()) {
      adapter.stop();
    }
  }

  @Override
  public void addAppVersion(AppVersion appVersion) throws FileNotFoundException {
    for (ServletEngineAdapter adapter : adapterMap.values()) {
      adapter.addAppVersion(appVersion);
    }
  }

  @Override
  public void deleteAppVersion(AppVersion appVersion) {
    for (ServletEngineAdapter adapter : adapterMap.values()) {
      adapter.deleteAppVersion(appVersion);
    }
  }

  @Override
  public void serviceRequest(UPRequest upRequest, UPResponse upResponse)
      throws ServletException, IOException {
////
 ////   adapter.serviceRequest(upRequest, upResponse);
  }

  @Override
  public void setSessionStoreFactory(SessionStoreFactory factory) {
    for (ServletEngineAdapter adapter : adapterMap.values()) {
      adapter.setSessionStoreFactory(factory);
    }  }
}
