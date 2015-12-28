package com.google.apphosting.runtime.jetty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

public class RpcEndPoint implements EndPoint {

  private final long created=System.currentTimeMillis();
  private final UPRequest upRequest;
  private final UPResponse upResponse;
  private volatile boolean closed;
  private volatile Connection connection;
  private volatile long idleTimeout;
  
  public RpcEndPoint(UPRequest upRequest, UPResponse upResponse) {
    super();
    this.upRequest = upRequest;
    this.upResponse = upResponse;
  }

  public UPRequest getUpRequest() {
    return upRequest;
  }

  public UPResponse getUpResponse() {
    return upResponse;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return InetSocketAddress.createUnresolved("localhost", -1);
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return InetSocketAddress.createUnresolved("localhost", -1);
  }

  @Override
  public boolean isOpen() {
    return !closed;
  }

  @Override
  public long getCreatedTimeStamp() {
    return created;
  }

  @Override
  public void shutdownOutput() {
    closed=true;
  }

  @Override
  public boolean isOutputShutdown() {
    return closed;
  }

  @Override
  public boolean isInputShutdown() {
    return closed;
  }

  @Override
  public void close() {
    closed=true;
  }

  @Override
  public int fill(ByteBuffer buffer) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean flush(ByteBuffer... buffer) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getTransport() {
    return this;
  }

  @Override
  public long getIdleTimeout() {
    return idleTimeout;
  }

  @Override
  public void setIdleTimeout(long idleTimeout) {
    this.idleTimeout=idleTimeout;
  }

  @Override
  public void fillInterested(Callback callback) throws ReadPendingException {
    throw new UnsupportedOperationException();

  }

  @Override
  public void write(Callback callback, ByteBuffer... buffers)
      throws WritePendingException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public void setConnection(Connection connection) {
    this.connection=connection;
  }

  @Override
  public void onOpen() {
  }

  @Override
  public void onClose() {
  }

  @Override
  public boolean isFillInterested() {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public boolean isOptimizedForDirectBuffers() {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void upgrade(Connection cnctn) {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

}
