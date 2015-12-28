package com.google.apphosting.runtime.jetty;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class RpcConnectionFactory extends AbstractLifeCycle implements
    ConnectionFactory {

  @Override
  public String getProtocol() {
    return "RPC";
  }

  @Override
  public Connection newConnection(Connector connector, EndPoint endPoint) {
    return new RpcConnection((RpcConnector)connector,(RpcEndPoint)endPoint);
  }

}
