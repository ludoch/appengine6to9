
package com.google.apphosting.runtime.jetty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

import com.google.appengine.repackaged.com.google.api.client.http.HttpMethods;
import com.google.apphosting.runtime.AppVersion;

/**
 * {@code ResourceFileServlet} is a copy of {@code
 * org.eclipse.jetty.servlet.DefaultServlet} that has been trimmed
 * down to only support the subset of features that we want to take
 * advantage of (e.g. no gzipping, no chunked encoding, no buffering,
 * etc.).  A number of Jetty-specific optimizations and assumptions
 * have also been removed (e.g. use of custom header manipulation
 * API's, use of {@code ByteArrayBuffer} instead of Strings, etc.).
 *
 * A few remaining Jetty-centric details remain, such as use of the
 * {@link ContextHandler.SContext} class, and Jetty-specific request
 * attributes, but these are specific cases where there is no
 * servlet-engine-neutral API available.  This class also uses Jetty's
 * {@link Resource} class as a convenience, but could be converted to
 * use {@link ServletContext#getResource(String)} instead.
 *
 */
public class ResourceFileServlet extends HttpServlet {
  private static final Logger logger =
      Logger.getLogger(ResourceFileServlet.class.getName());

  // TODO(schwardo): Sync up with static file handler code in PFE.
  private static final String CACHE_CONTROL_VALUE = "private";

  private Resource resourceBase;
  private String[] welcomeFiles;
  ContextHandler chandler;

  /**
   * Initialize the servlet by extracting some useful configuration
   * data from the current {@link ServletContext}.
   */
  public void init() throws ServletException {
    ServletContext context =  getServletContext();
    AppVersion appVersion = (AppVersion) context.getAttribute(
        JettyConstants.APP_VERSION_CONTEXT_ATTR);
    
    chandler = ((ContextHandler.Context)context).getContextHandler();
    
    // AFAICT, there is no real API to retrieve this information, so
    // we access Jetty's internal state.
    welcomeFiles = chandler.getWelcomeFiles();

    try {
      resourceBase = Resource.newResource(
          context.getResource(URIUtil.SLASH + appVersion.getPublicRoot()));
    } catch (MalformedURLException ex) {
      logger.log(Level.WARNING, "Could not initialize:", ex);
      throw new ServletException(ex);
    } catch (IOException ex) {
      logger.log(Level.WARNING, "Could not initialize:", ex);
      throw new ServletException(ex);
    }
  }

  /**
   * Retrieve the static resource file indicated.
   */
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    String servletPath;
    String pathInfo;

    boolean included =  request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)!=null;
    if (included) {
      servletPath = (String) request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
      pathInfo = (String) request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
      if (servletPath == null) {
        servletPath = request.getServletPath();
        pathInfo = request.getPathInfo();
      }
    } else {
      included = Boolean.FALSE;
      servletPath = request.getServletPath();
      pathInfo = request.getPathInfo();
    }

    boolean forwarded = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)!=null;

    String pathInContext = URIUtil.addPaths(servletPath, pathInfo);
    // TODO(meder): Temporary fix for CERT VU#402580 (Jetty directory traversal
    // TODO(meder) vulnerability).
    // URIUtil.canonicanPath() assumes that path passed to it can contain query
    // elements (e.g. /foo/bar?aaa=bbb), but getServletPath() and getPathInfo()
    // never include query string. We perform URI resolution and get rid of all
    // '..' sequences against the URL of current request. This results in
    // files requested in directories ending with '?' to be reported as not
    // found. This, however, shouldn't break anything, since such directories
    // didn't work prior to this change anyway.

////////////    Uri resolvedUri = UriResolvers.resolve(Uri.parse(request.getRequestURL().toString()),
////////////            Uri.parse(URIUtil.encodePath(pathInContext)));

////////////    logger.info("Resolved " + pathInContext + " to " + resolvedUri.getPath());
////////////    pathInContext = resolvedUri.decodePercent(resolvedUri.getPath());

    // The servlet spec says "No file contained in the WEB-INF
    // directory may be served directly a client by the container.
    // However, ... may be exposed using the RequestDispatcher calls."
    // Thus, we only allow these requests for includes and forwards.
    //
    // TODO(schwardo): I suspect we should allow error handlers here somehow.
    if (isProtectedPath(pathInContext) && !included && !forwarded) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    if (maybeServeWelcomeFile(pathInContext, included, request, response)) {
      // We served a welcome file (either via redirecting, forwarding, or including).
      return;
    }

    if (pathInContext.endsWith(URIUtil.SLASH)) {
      // N.B.(schwardo): Resource.addPath() trims off trailing
      // slashes, which may result in us serving files for strange
      // paths (e.g. "/index.html/").  Since we already took care of
      // welcome files above, we just return a 404 now if the path
      // ends with a slash.
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    // RFC 2396 specifies which characters are allowed in URIs:
    //
    // http://tools.ietf.org/html/rfc2396#section-2.4.3
    //
    // See also RFC 3986, which specifically mentions handling %00,
    // which would allow security checks to be bypassed.
    for (int i = 0; i < pathInContext.length(); i++) {
      int c = pathInContext.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        logger.warning("Attempted to access file containg control character, returning 400.");
        return;
      }
    }

    // Find the resource
    Resource resource = null;
    try {
      resource = getResource(pathInContext);

      if (resource == null) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
      }

      if (StringUtil.endsWithIgnoreCase(resource.getName(), ".jsp")) {
        // General paranoia: don't ever serve raw .jsp files.
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
      }

      // Handle resource
      if (resource.isDirectory()) {
        if (included || passConditionalHeaders(request, response, resource)) {
          response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
      } else {
        if (resource == null || !resource.exists()) {
          logger.warning("Non existent resource: " + pathInContext + " = " + resource);
          response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } else {
          if (included || passConditionalHeaders(request, response, resource)) {
            sendData(request, response, included, resource);
          }
        }
      }
    } finally {
      if (resource != null) {
        resource.release();
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    doGet(request,response);
  }

  protected boolean isProtectedPath(String target) {
        target = target.toLowerCase();
        return target.contains("/web-inf/") || target.contains("/meta-inf/");
  }

  /**
   * Get Resource to serve.
   * @param pathInContext The path to find a resource for.
   * @return The resource to serve.
   */
  private Resource getResource(String pathInContext) {
    try {
      if (resourceBase != null) {
        return resourceBase.addPath(pathInContext);
      }
    } catch (IOException ex) {
      logger.log(Level.WARNING, "Could not find: " + pathInContext, ex);
    }
    return null;
  }

  /**
   * Finds a matching welcome file for the supplied path and, if
   * found, serves it to the user.  This will be the first entry in
   * the list of configured {@link #welcomeFiles welcome files} that
   * exists within the directory referenced by the path.  If the
   * resource is not a directory, or no matching file is found, then
   * <code>null</code> is returned.  The list of welcome files is read
   * from the {@link ContextHandler} for this servlet, or
   * <code>"index.jsp" , "index.html"</code> if that is
   * <code>null</code>.
   * @param resource
   * @return true if a welcome file was served, false otherwise
   * @throws IOException
   * @throws MalformedURLException
   */
  private boolean maybeServeWelcomeFile(String path,
                                        boolean included,
                                        HttpServletRequest request,
                                        HttpServletResponse response)
      throws MalformedURLException, IOException, ServletException {
    if (welcomeFiles == null) {
      System.err.println("No welcome files");
      return false;
    }

    // Add a slash for matching purposes.  If we needed this slash, we
    // are not doing an include, and we're not going to redirect
    // somewhere else we'll redirect the user to add it later.
    if (!path.endsWith(URIUtil.SLASH)) {
      path += URIUtil.SLASH;
    }

    AppVersion appVersion = (AppVersion) getServletContext().getAttribute(
        JettyConstants.APP_VERSION_CONTEXT_ATTR);
    ServletHandler shandler = chandler.getChildHandlerByClass(ServletHandler.class);
    
    
    PathMap.MappedEntry<ServletHolder> defaultEntry = shandler.getHolderEntry("/");

    for (String welcomeName : welcomeFiles) {
      String welcomePath = path + welcomeName;
      String relativePath = welcomePath.substring(1);

      if (shandler.getHolderEntry(welcomePath) != defaultEntry) {
        // It's a path mapped to a servlet.  Forward to it.
        RequestDispatcher dispatcher = request.getRequestDispatcher(path + welcomeName);
        return serveWelcomeFileAsForward(dispatcher, included, request, response);
      }
      if (appVersion.isResourceFile(relativePath)) {
        // It's a resource file.  Forward to it.
        RequestDispatcher dispatcher = request.getRequestDispatcher(path + welcomeName);
        return serveWelcomeFileAsForward(dispatcher, included, request, response);
      }
      if (appVersion.isStaticFile(relativePath)) {
        // It's a static file (served from blobstore).  Redirect to it
        return serveWelcomeFileAsRedirect(path + welcomeName, included, request, response);
      }
      RequestDispatcher namedDispatcher = getServletContext().getNamedDispatcher(welcomeName);
      if (namedDispatcher != null) {
        // It's a servlet name (allowed by Servlet 2.4 spec).  We have
        // to forward to it.
        return serveWelcomeFileAsForward(namedDispatcher, included, request, response);
      }
    }

    return false;
  }

  private boolean serveWelcomeFileAsRedirect(String path,
                                             boolean included,
                                             HttpServletRequest request,
                                             HttpServletResponse response)
      throws IOException {
    if (included) {
      // This is an error.  We don't have the file so we can't
      // include it in the request.
      return false;
    }

    // Even if the trailing slash is missing, don't bother trying to
    // add it.  We're going to redirect to a full file anyway.
    response.setContentLength(0);
    String q = request.getQueryString();
    if (q != null && q.length() != 0) {
      response.sendRedirect(path + "?" + q);
    } else {
      response.sendRedirect(path);
    }
    return true;
  }

  private boolean serveWelcomeFileAsForward(RequestDispatcher dispatcher,
                                            boolean included,
                                            HttpServletRequest request,
                                            HttpServletResponse response)
      throws IOException, ServletException {
    // If the user didn't specify a slash but we know we want a
    // welcome file, redirect them to add the slash now.
    if (!included && !request.getRequestURI().endsWith(URIUtil.SLASH)) {
      redirectToAddSlash(request, response);
      return true;
    }

    if (dispatcher != null) {
      if (included) {
        dispatcher.include(request, response);
      } else {
        dispatcher.forward(request, response);
      }
      return true;
    }
    return false;
  }

  private void redirectToAddSlash(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    StringBuffer buf = request.getRequestURL();
    int param = buf.lastIndexOf(";");
    if (param < 0) {
      buf.append('/');
    } else {
      buf.insert(param, '/');
    }
    String q = request.getQueryString();
    if (q != null && q.length() != 0) {
      buf.append('?');
      buf.append(q);
    }
    response.setContentLength(0);
    response.sendRedirect(response.encodeRedirectURL(buf.toString()));
  }

  /**
   * Check the headers to see if content needs to be sent.
   * @return true if the content should be sent, false otherwise.
   */
  private boolean passConditionalHeaders(HttpServletRequest request,
                                         HttpServletResponse response,
                                         Resource resource) throws IOException {
    if (!request.getMethod().equals(HttpMethods.HEAD)) {
      String ifms = request.getHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
      if (ifms != null) {
        long ifmsl = -1;
        try {
          ifmsl = request.getDateHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
        } catch (IllegalArgumentException e) {
          // Ignore bad date formats.
        }
        if (ifmsl != -1) {
          if (resource.lastModified() <= ifmsl) {
            response.reset();
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.flushBuffer();
            return false;
          }
        }
      }

      // Parse the if[un]modified dates and compare to resource
      long date = -1;
      try {
        date = request.getDateHeader(HttpHeader.IF_UNMODIFIED_SINCE.asString());
      } catch (IllegalArgumentException e) {
         // Ignore bad date formats.
      }
      if (date != -1) {
        if (resource.lastModified() > date) {
          response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Write or include the specified resource.
   */
  private void sendData(HttpServletRequest request,
                        HttpServletResponse response,
                        boolean include,
                        Resource resource) throws IOException {
    long contentLength = resource.length();
    if (!include) {
      writeHeaders(response, resource, contentLength);
    }

    // Get the output stream (or writer)
    OutputStream out = null;
    try {
      out = response.getOutputStream();}
    catch (IllegalStateException e) {
      out = new WriterOutputStream(response.getWriter());
    }
    resource.writeTo(out, 0, contentLength);
  }

  /**
   * Write the headers that should accompany the specified resource.
   */
  private void writeHeaders(HttpServletResponse response, Resource resource, long count)
      throws IOException {
    String contentType = getServletContext().getMimeType(resource.getName());
    if (contentType != null) {
      response.setContentType(contentType);
    }

    if (count != -1) {
      if (count < Integer.MAX_VALUE) {
        response.setContentLength((int) count);
      } else {
        response.setContentLengthLong(count);
      }
    }

    response.setDateHeader(HttpHeader.LAST_MODIFIED.asString(), resource.lastModified());
    response.setHeader(HttpHeader.CACHE_CONTROL.asString(), CACHE_CONTROL_VALUE);
  }
}
