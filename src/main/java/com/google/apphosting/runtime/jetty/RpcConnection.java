
package com.google.apphosting.runtime.jetty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.QueuedHttpInput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.webapp.WebAppContext;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.HttpPb.HttpRequest;
import com.google.apphosting.base.HttpPb.HttpResponse;
import com.google.apphosting.base.HttpPb.ParsedHttpHeader;
import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

/**
 * A custom version of HttpConnection that uses UPRequestParser and
 * UPResponseGenerator instead of the standard HTTP stream parser and
 * generator.
 *
 */
public class RpcConnection implements Connection, HttpTransport {
  private static final Logger logger = Logger.getLogger(RpcConnection.class.getName());

  // This should be kept in sync with HTTPProto::X_GOOGLE_INTERNAL_SKIPADMINCHECK.
  private static final String X_GOOGLE_INTERNAL_SKIPADMINCHECK =
      "X-Google-Internal-SkipAdminCheck";

  // Keep in sync with com.google.apphosting.utils.jetty.AppEngineAuthentication.
  private static final String SKIP_ADMIN_CHECK_ATTR =
      "com.google.apphosting.internal.SkipAdminCheck";
  
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();
  private final RpcConnector connector;
  private final RpcEndPoint endPoint;
  private final UPResponse upResponse;
  private ByteBuffer aggregate;

  public RpcConnection(RpcConnector connector, RpcEndPoint endpoint) {
    this.connector=connector;
    this.endPoint=endpoint;
    this.upResponse=endpoint.getUpResponse();
  }
  
  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void onOpen() {
    for (Listener listener : listeners)
        listener.onOpened(this);
  }
  
  @Override
  public void onClose() {
    for (Listener listener : listeners)
        listener.onClosed(this);
  }

  @Override
  public EndPoint getEndPoint() {
    return endPoint;
  }
  
  @Override
  public void close() {
    endPoint.close();
  }
  
  @Override
  public int getMessagesIn() {
    return 1;
  }
  
  @Override
  public int getMessagesOut() {
    return 1;
  }
  
  @Override
  public long getBytesIn() {
    return 0;
  }
  
  @Override
  public long getBytesOut() {
    return 0;
  }
  
  @Override
  public long getCreatedTimeStamp() {
    return endPoint.getCreatedTimeStamp();
  }
  
  public void handle(AppVersionKey appVersionKey) 
      throws ServletException, IOException {

    HttpInput<ByteBuffer> input=new QueuedHttpInput<ByteBuffer>()
        {
          @Override
          protected void onContentConsumed(ByteBuffer item) {            
          }

          @Override
          protected int remaining(ByteBuffer item) {
            return item.remaining();
          }

          @Override
          protected int get(ByteBuffer item, byte[] buffer, int offset,int length) {
            int l = Math.min(item.remaining(), length);
            item.get(buffer, offset, l);
            return l;
          }

          @Override
          protected void consume(ByteBuffer item, int length) {
            item.position(item.position()+length);
          }
        };
        
    HttpChannel<ByteBuffer> channel = new HttpChannel<ByteBuffer>(connector, connector.getHttpConfiguration(), endPoint, this, input);
    Request request = channel.getRequest();
    HttpRequest rpc = endPoint.getUpRequest().getRequest();
    
    // disable async
    request.setAsyncSupported(false);

    // is this SSL
    if (rpc.isIsHttps())
    {
      request.setScheme(HttpScheme.HTTPS.asString());
      request.setSecure(true);
    }
    
    // pretend to parse the request line
    HttpMethod method = HttpMethod.CACHE.getBest(rpc.getProtocol(), 0, rpc.getProtocol().length);
    String methodS = method!=null?method.asString():new String(rpc.getProtocol(), 0, rpc.getProtocol().length,StandardCharsets.ISO_8859_1);
    HttpVersion version = HttpVersion.CACHE.getBest(rpc.getHttpVersion(), 0, rpc.getHttpVersion().length);
    channel.startRequest(method,methodS,BufferUtil.toBuffer(rpc.getUrl()),version);

    // pretend to parse the header fields
    for (ParsedHttpHeader header : rpc.headerss())
    {
      // Optimize field creation by looking for known headers and header values
      HttpHeader h = HttpHeader.CACHE.getBest(header.getKey());
      byte[] v = (byte[])header.getValue();
      final HttpField field;
      
      if (h==null)
        field=new HttpField(header.getKey(),new String(v,0,v.length,StandardCharsets.ISO_8859_1));
      else
      {
        HttpHeaderValue hv = HttpHeaderValue.hasKnownValues(h)?HttpHeaderValue.CACHE.getBest(v, 0, v.length):null;
        if (hv==null || hv.asString().length()!=v.length)
          field=new HttpField(h,new String(v,0,v.length,StandardCharsets.ISO_8859_1));
        else
          field=new HttpField(h,hv);
      }
      channel.parsedHeader(field);
    }
    
    // end of headers. This should return true to indicate that we are good to continue handling
    if (!channel.headerComplete())
      throw new ServletException("cannot handle?");

    // give the input any post content
    byte[] postdata = rpc.getPostdataAsBytes();
    if (postdata!=null)
      input.content(BufferUtil.toBuffer(postdata));
    
    // signal the end of the request
    channel.messageComplete();

    // Tell AppVersionHandlerMap which app version should handle this
    // request.
    request.setAttribute(JettyConstants.APP_VERSION_KEY_REQUEST_ATTR, appVersionKey);

    // Translate the X-Google-Internal-SkipAdminCheck to a servlet attribute.
    if (hasSkipAdminCheck(endPoint.getUpRequest())) {
      request.setAttribute(SKIP_ADMIN_CHECK_ATTR, Boolean.TRUE);

      // N.B.(schwardo): If SkipAdminCheck is set, we're actually lying
      // to Jetty here to tell it that HTTPS is in use when it may not
      // be.  This is useful because we want to bypass Jetty's
      // transport-guarantee checks (to match Python, which bypasses
      // handler_security: for these requests), but unlike
      // authentication SecurityHandler does not provide an easy way to
      // plug in custom logic here.  I do not believe that our lie is
      // user-visible (ServletRequest.getProtocol() is unchanged).
      request.setSecure(true);
    }

    // This will invoke a servlet and mutate upResponse before returning.
    channel.handle();
    
    // If an exception occurred while running GenericServlet.service,
    // this attribute will be set and then our WebAppContext's
    // ErrorHandler will be invoked.
    Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

    if (exception != null && !hasExceptionHandledByErrorPage(request)) {
      // We will most likely have set something here, but the
      // AppServer will only do the right thing (print stack traces
      // for admins, generic Prometheus error message others) if this
      // is completely unset.
      upResponse.clearHttpResponse();

      if (exception instanceof ServletException) {
        throw (ServletException) exception;
      } else {
        throw new ServletException(exception);
      }
    }
  }

  
  /**
   * Returns true if the X-Google-Internal-SkipAdminCheck header is
   * present.  This header is passed via the set of protected headers
   * that is made available to the runtime but not to user code.  Note
   * that like the AppServer code, we only check if the header is
   * present and not the value itself.
   */
  private boolean hasSkipAdminCheck(UPRequest upRequest) {
    for (ParsedHttpHeader header : upRequest.runtimeHeaderss()) {
      if (header.getKey().equalsIgnoreCase(X_GOOGLE_INTERNAL_SKIPADMINCHECK)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if the exception has been explicitly handled by an "error" page of the webapp.
   *
   * @return true iff the exception has already been handled by the "current" error page.
   */
  private boolean hasExceptionHandledByErrorPage(Request servletRequest) {
    Object errorPage = servletRequest.getAttribute(WebAppContext.ERROR_PAGE);
    Object errorPageHandled = servletRequest.getAttribute(AppVersionHandlerMap.ERROR_PAGE_HANDLED);
    if (errorPage != null && errorPage.equals(errorPageHandled)) {
      return true;
    }
    return false;
  }

  
  @Override
  public void send(ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback) {

    HttpResponse httpRes = upResponse.getMutableHttpResponse();
    httpRes.setResponsecode(info.getStatus());
    for (HttpField field : info.getHttpFields())
    {
      ParsedHttpHeader promHeader = new ParsedHttpHeader();
      promHeader.setKey(field.getName());
      promHeader.setValue(field.getValue());
      httpRes.addOutputHeaders(promHeader);
    }
    
    send(content,lastContent,callback);
  }

  @Override
  public void send(ByteBuffer content, boolean lastContent, Callback callback) {
    if (BufferUtil.hasContent(content))
    {
      // TODO (gregw) have to evaluate how efficient this code is behind the HttpOutput aggregate buffer.
      // Data is essentially being aggregated twice.   It might be more efficient just to grow the HttpOutput 
      // aggregate buffer and avoid the second copy.  This would also work better for avoiding commits from buffer
      // overflow
      
      // Do we have a buffer to copy into?
      if (aggregate == null)
      {
        // if this is the last content, then just get a buffer big enough for the content.
        // TODO (gregw) maybe the copy can be avoided for the last content if it is also the first?
        // otherwise size is max of standard output buffer size and the content size 
        int size = lastContent?content.remaining():Math.max(connector.getHttpConfiguration().getOutputBufferSize(),content.remaining());
        aggregate = connector.getByteBufferPool().acquire(size, false);
      }
      else if (BufferUtil.space(aggregate)<content.remaining())
      {
        // if the space left in the aggregate is too small, then create a new buffer that is incremente by the max of the standard buffer 
        // size and the content.remaining size.
        int size = aggregate.capacity() + Math.max(connector.getHttpConfiguration().getOutputBufferSize(), content.remaining());
        ByteBuffer bigger = connector.getByteBufferPool().acquire(size, false);
        BufferUtil.append(bigger, aggregate);
        connector.getByteBufferPool().release(aggregate);
        aggregate=bigger;
      }
      BufferUtil.append(content, aggregate);
    }
    callback.succeeded();
  }

  @Override
  public void completed() {
    byte[] bytes;
    
    if (BufferUtil.hasContent(aggregate))
    {
      bytes=BufferUtil.toArray(aggregate);
      connector.getByteBufferPool().release(aggregate);
      aggregate=null;
    }
    else
      bytes=new byte[0];
    
    upResponse.getMutableHttpResponse().setResponseAsBytes(bytes);
    
  }

  @Override
  public void abort() {    
    completed();
  }
}
