
package com.google.apphosting.runtime.jetty;

import java.io.IOException;
import javax.servlet.ServletException;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

/**
 * {@code RpcConnector} is an {@link AbstractConnector} that
 * essentially does nothing.  In particular, it does not open a local
 * socket and does not start any background threads for accepting new
 * connections.
 *
 * <p>It exists primarily to satisfy various low-level Jetty
 * components that expect each {@code Connection} to have a {@code
 * Connector} and for them to share an {@code EndPoint}.
 *
 * <p>This {@link AbstractConnector} has no intrinsic transport
 * guarantees.  Instead, it checks the scheme of each {@link Request}
 * to determine whether HTTPS was used, and if so, indicates that both
 * integrity and confidentiality are guaranteed.
 *
 * <p>This class is loosely based on {@link
 * org.eclipse.jetty.server.LocalConnector}, but we don't extend it because
 * it still does some things that we don't want (e.g. accepting
 * connections).
 *
 */
public class RpcConnector extends AbstractConnector {

  private final HttpConfiguration httpConfiguration = new HttpConfiguration();
  private final AppVersionHandlerMap appVersionHandlerMap;
  
  public RpcConnector(Server server,AppVersionHandlerMap appVersionHandlerMap)
  {
    super(server,null,null,null,0,new RpcConnectionFactory());
    this.appVersionHandlerMap=appVersionHandlerMap;
  }

  public HttpConfiguration getHttpConfiguration() {
    return httpConfiguration;
  }
  
  /**
   * This method is unnecessary.
   * @throws UnsupportedOperationException
   */
  @Override
  protected void accept(int acceptorID)
      throws IOException, InterruptedException {
    // Because we don't call AbstractConnector.doStart(), we won't spawn
    // acceptor threads and thus this method won't ever be called.
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getTransport() {
	return null;
  }
  

  public void serviceRequest(final UPRequest upRequest, final UPResponse upResponse)
      throws ServletException, IOException {
    AppVersionKey appVersionKey = AppVersionKey.fromUpRequest(upRequest);

    Handler handler = appVersionHandlerMap.getHandler(appVersionKey);
    // We don't want to call the Handler directly -- we need to do it
    // through RpcConnection so various ThreadLocal's get set up
    // correctly.  However, we do want to check that a Handler is
    // registered so we can deal with that error condition ourself.
    if (handler == null) {
      upResponse.setErrorMessage("Unknown app: " + appVersionKey);
      return;
    }
    
    RpcEndPoint endPoint = new RpcEndPoint(upRequest, upResponse);
    
    RpcConnection connection = (RpcConnection)getDefaultConnectionFactory().newConnection(this, endPoint);
    endPoint.setConnection(connection);
    
    connection.handle(appVersionKey);
  }
}
