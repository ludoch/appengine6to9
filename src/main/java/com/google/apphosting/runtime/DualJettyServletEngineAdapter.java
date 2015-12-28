
package com.google.apphosting.runtime;

import com.google.apphosting.base.RuntimePb;
//import com.google.apphosting.runtime.jetty9.JettyServletEngineAdapter;
import com.google.apphosting.utils.config.ServletVersionChecker;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;

/**
 * @author ludo@google.com (ludovic Champenois)
 */
public class DualJettyServletEngineAdapter implements ServletEngineAdapter {
  private static final Logger logger =
      Logger.getLogger(DualJettyServletEngineAdapter.class.getName());
  private com.google.apphosting.runtime.jetty.JettyServletEngineAdapter jetty6
      = new com.google.apphosting.runtime.jetty.JettyServletEngineAdapter();
 private  com.google.apphosting.runtime.jetty.JettyServletEngineAdapter jetty9  = new  com.google.apphosting.runtime.jetty.JettyServletEngineAdapter();
  private ServletEngineAdapter activeAdapter;

  @Override
  public void start(String serverInfo) {
    jetty6.start(serverInfo);
   jetty9.start(serverInfo);
  }




  @Override
  public void stop() {
    if (activeAdapter != null) {
      activeAdapter.stop();
    }
  }

  @Override
  public void addAppVersion(AppVersion appVersion) throws FileNotFoundException {

    int version = ServletVersionChecker.getServletVersion(appVersion.getRootDirectory());

    version = 3;
    if (version == 3) {
      jetty6.stop();
      activeAdapter = jetty9;
    } else {
      jetty9.stop();
      activeAdapter = jetty6;
    }

    activeAdapter.addAppVersion(appVersion);
  }

  @Override
  public void deleteAppVersion(AppVersion appVersion) {
    if (activeAdapter != null) {
      activeAdapter.deleteAppVersion(appVersion);
    }
  }

  @Override
  public void serviceRequest(RuntimePb.UPRequest upRequest, RuntimePb.UPResponse upResponse)
      throws ServletException, IOException {
    if (activeAdapter != null) {
      activeAdapter.serviceRequest(upRequest, upResponse);
    }
  }

  @Override
  public void setSessionStoreFactory(SessionStoreFactory factory) {
    // need both as we don't know yet which one will be used:
    jetty6.setSessionStoreFactory(factory);
    jetty9.setSessionStoreFactory(factory);
  }
}
