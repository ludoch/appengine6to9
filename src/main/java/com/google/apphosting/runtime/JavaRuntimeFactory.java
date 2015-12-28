package com.google.apphosting.runtime;

import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.base.RuntimePb;
import com.google.apphosting.runtime.security.ApplicationEnvironment;
import java.io.File;

public class JavaRuntimeFactory {

  private JavaRuntimeFactory() {
  }

  public static void main(String args[]) {
    RuntimeLogSink logSink = new RuntimeLogSink(42L);

    ServletEngineAdapter servletEngine = createServletEngine(args);

    RuntimePb.APIHost.ClientInterface apiHost = null;

    BackgroundRequestCoordinator coordinator = new BackgroundRequestCoordinator();

    ApiProxyImpl apiProxyImpl = new ApiProxyImpl(
            apiHost,
            null,
            "aa",
            555,
            555,
            coordinator,
            false);

    RequestManager requestManager = new RequestManager(
            4,
            55,
            true,
            logSink,
            apiProxyImpl,
            3000,
            true);

    apiProxyImpl.setRequestManager(requestManager);

    ApplicationEnvironment.RuntimeConfiguration configuration
            = new ApplicationEnvironment.RuntimeConfiguration();

    JavaRuntime runtime = new JavaRuntime(
            servletEngine,
            8080,
            null,
            null,
            new File("approot/"),
            new File("builtins"),
            requestManager,
            "Google App Engine/",
            true,
            null,
            configuration,
            false,
            null,
            coordinator,
            true);

    ApiProxy.setDelegate(apiProxyImpl);

    try {
      runtime.start();
    } catch (Exception e) {
      try {
        runtime.stop();
      } catch (Throwable th) {
        // Swallow this exception -- the other one is what matters.
      }
      throw new RuntimeException("Could not start server", e);
    }
  }

  /**
   * Creates the ServletEngineAdapter specifies by the --servlet_engine flag.
   */
  private static ServletEngineAdapter createServletEngine(String[] args) {
    // was SERVLET_ENGINE.get();
    Class<? extends ServletEngineAdapter> engineClazz = DualJettyServletEngineAdapter.class;
    try {
      return engineClazz.newInstance();
    } catch (InstantiationException ex) {
      throw new RuntimeException("Failed to instantiate " + engineClazz, ex);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException("Not allowed to instantiate " + engineClazz, ex);
    }
  }

}
