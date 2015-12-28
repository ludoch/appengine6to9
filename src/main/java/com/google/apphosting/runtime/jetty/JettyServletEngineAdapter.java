
package com.google.apphosting.runtime.jetty;

import java.io.FileNotFoundException;
import java.io.IOException;

import javax.servlet.ServletException;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;

import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;
import com.google.apphosting.runtime.AppVersion;
import com.google.apphosting.runtime.ServletEngineAdapter;
import com.google.apphosting.runtime.SessionStore;
import com.google.apphosting.runtime.SessionStoreFactory;
import com.google.apphosting.utils.jetty.JettyLogger;

/**
 * This is an implementation of ServletEngineAdapter that uses the
 * third-party Jetty servlet engine.
 *
 */
public class JettyServletEngineAdapter implements ServletEngineAdapter {

  // Tell Jetty to use our custom logging class (that forwards to
  // java.util.logging) instead of writing to System.err.
  static {
    System.setProperty("org.eclipse.jetty.log.class", JettyLogger.class.getName());
  };

  private Server server;
  private RpcConnector rpcConnector;
  private AppVersionHandlerMap appVersionHandlerMap;
  
  @Override
  public void start(String serverInfo) {
    server = new Server();
    
    appVersionHandlerMap=new AppVersionHandlerMap(server, serverInfo);
    
    rpcConnector = new RpcConnector(server,appVersionHandlerMap);
    server.setConnectors(new Connector[] { rpcConnector });
    server.setHandler(appVersionHandlerMap);

    try {
      server.start();
    } catch (Exception ex) {
      // TODO(schwardo): Should we have a wrapper exception for this
      // type of thing in ServletEngineAdapter?
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void stop() {
    try {
      server.stop();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void addAppVersion(AppVersion appVersion) throws FileNotFoundException {
    appVersionHandlerMap.addAppVersion(appVersion);
  }

  @Override
  public void deleteAppVersion(AppVersion appVersion) {
    appVersionHandlerMap.removeAppVersion(appVersion.getKey());
  }
  
  /**
   * Sets the {@link SessionStoreFactory} that will be used to create the list
   * of {@link SessionStore SessionStores} to which the HTTP Session will be
   * stored, if sessions are enabled. This method must be invoked after
   * {@link #start(String)}.
   */
  @Override
  public void setSessionStoreFactory(SessionStoreFactory factory) {
    appVersionHandlerMap.setSessionStoreFactory(factory);
  }

  @Override
  public void serviceRequest(final UPRequest upRequest, final UPResponse upResponse)
      throws ServletException, IOException {
    rpcConnector.serviceRequest(upRequest, upResponse);
  }

}
