
package com.google.apphosting.runtime.jetty;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.webapp.WebAppContext;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.runtime.AppVersion;
import com.google.apphosting.runtime.SessionStore;
import com.google.apphosting.runtime.SessionStoreFactory;
import com.google.apphosting.runtime.SessionsConfig;
import com.google.apphosting.utils.jetty.RuntimeAppEngineWebAppContext;
import com.google.apphosting.utils.jetty.StubSessionManager;

/**
 * {@code AppVersionHandlerMap} is a {@code HandlerContainer} that
 * identifies each child {@code Handler} with a particular {@code
 * AppVersionKey}.
 *
 * <p>In order to identify which application version each request
 * should be sent to, this class assumes that an attribute will be set
 * on the {@code HttpServletRequest} with a value of the {@code
 * AppVersionKey} that should be used.
 *
 */
public class AppVersionHandlerMap extends AbstractHandlerContainer {
  private static final Logger log =
      Logger.getLogger(AppVersionHandlerMap.class.getName());

  /**
   * Any settings in this webdefault.xml file will be inherited by all
   * applications.  We don't want to use Jetty's built-in
   * webdefault.xml because we want to disable some of their
   * functionality, and because we want to be explicit about what
   * functionality we are supporting.
   */
  public static final String WEB_DEFAULTS_XML =
      "com/google/apphosting/runtime/jetty/webdefault.xml";

  /**
   * Specify which {@link org.eclipse.jetty.server.webapp.Configuration} objects should be
   * invoked when configuring a web application.
   *
   * <p>This is a subset of:
   *   org.eclipse.jetty.webapp.WebAppContext.__dftConfigurationClasses
   *
   * <p>Specifically, we've removed {@link
   * org.eclipse.jetty.server.webapp.JettyWebXmlConfiguration} which allows
   * users to use {@code jetty-web.xml} files.  We definitely do not
   * want to allow these files, as they allow for arbitrary method
   * invocation.
   *
   * <p>In addition, we've removed {@link
   * org.eclipse.jetty.server.webapp.JettyWebInfConfiguration} as its sole
   * purpose is to configure the {@link ClassLoader}, and we do that
   * ourselves.
   */
  private static final String CONFIG_CLASSES[] = new String[] {
        "org.eclipse.jetty.server.webapp.WebXmlConfiguration",
        "org.eclipse.jetty.server.webapp.TagLibConfiguration"
  };

  /**
   * An accessible {@link Field} that points to{@link
   * WebAppContext#_unavailable}.  This is a temporary hack to work
   * around the fact that Jetty 6.1.5 does not expose filter
   * initialization failures to us.
   *
   * TODO(schwardo): When we upgrade to Jetty 6.1.10 we can just call
   * {@link WebAppContext#getUnavailableException} instead.
   */
  private static final Field UNAVAILABLE_FIELD;
  static {
    try {
      UNAVAILABLE_FIELD = WebAppContext.class.getDeclaredField("_unavailable");
      UNAVAILABLE_FIELD.setAccessible(true);
    } catch (NoSuchFieldException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * A "private" request attribute to indicate if the dispatch to a most recent error
   * page has run to completion. Note an error page itself may generate errors.
   */
  static final String ERROR_PAGE_HANDLED = WebAppContext.ERROR_PAGE + ".handled";

  private final Server server;
  private final String serverInfo;
  private final Map<AppVersionKey, AppVersion> appVersionMap;
  private final Map<AppVersionKey, Handler> handlerMap;
  private SessionStoreFactory sessionStoreFactory = new SessionStoreFactory() {
    @Override
    public List<SessionStore> createSessionStores(SessionsConfig sessionsConfig) {
      DatastoreSessionStore datastoreSessionStore = sessionsConfig.isAsyncPersistence()
          ? new DeferredDatastoreSessionStore(sessionsConfig.getAsyncPersistenceQueueName())
          : new DatastoreSessionStore();
      // Write session data to the datastore before we write to memcache.
      return Arrays.asList(datastoreSessionStore, new MemcacheSessionStore());
    }
  };

  public AppVersionHandlerMap(Server server, String serverInfo) {
    this.server = server;
    this.serverInfo = serverInfo;
    this.appVersionMap = new HashMap<AppVersionKey, AppVersion>();
    this.handlerMap = new HashMap<AppVersionKey, Handler>();
  }

  public void addAppVersion(AppVersion appVersion) {
    appVersionMap.put(appVersion.getKey(), appVersion);
  }

  public void removeAppVersion(AppVersionKey appVersionKey) {
    appVersionMap.remove(appVersionKey);
  }

  /**
   * Sets the {@link SessionStoreFactory} that will be used for generating the
   * list of {@link SessionStore SessionStores} that will be passed to
   * {@link SessionManager} for apps for which sessions are enabled. This setter
   * is currently used only for testing purposes. Normally the default factory
   * is sufficient.
   */
  public void setSessionStoreFactory(SessionStoreFactory factory) {
    sessionStoreFactory = factory;
  }

  /**
   * Adds a {@code Handler} that will process requests for the
   * specified application version.
   *
   * @throws Exception Unfortunately, as {@code LifeCycle.start}
   * requires this.
   */
  public void addHandler(AppVersionKey appVersionKey, Handler handler) throws Exception {
    handler.setServer(getServer());
    if (!handler.isStarted()) {
      handler.start();
    }
    handlerMap.put(appVersionKey, handler);
  }

  /**
   * Removes the handler for the specified application version.
   *
   * @throws Exception Unfortunately, as {@code LifeCycle.stop}
   * requires this.
   */
  public void removeHandler(AppVersionKey appVersionKey) throws Exception {
    Handler handler = handlerMap.get(appVersionKey);
    if (handler != null) {
      handlerMap.remove(appVersionKey);
      if (handler.isStarted()) {
        handler.stop();
      }
    }
  }

  /**
   * Returns the {@code Handler} that will handle requests for the
   * specified application version.
   */
  public synchronized Handler getHandler(AppVersionKey appVersionKey) throws ServletException {
    Handler handler = handlerMap.get(appVersionKey);
    if (handler == null) {
      AppVersion appVersion = appVersionMap.get(appVersionKey);
      if (appVersion != null) {
        handler = createHandler(appVersion);
        handlerMap.put(appVersionKey, handler);
      }
    }
    return handler;
  }

  private Handler createHandler(AppVersion appVersion) throws ServletException {
    AppVersionKey appVersionKey = appVersion.getKey();
    try {
      File contextRoot = appVersion.getRootDirectory();

      WebAppContext context = new RuntimeAppEngineWebAppContext(contextRoot, getServerInfo());
      context.setServer(server);
      context.setDefaultsDescriptor(WEB_DEFAULTS_XML);
      context.setClassLoader(appVersion.getClassLoader());
      context.setErrorHandler(new NullErrorHandler());
      context.setConfigurationClasses(CONFIG_CLASSES);
      SessionsConfig sessionsConfig = appVersion.getSessionsConfig();
      if (sessionsConfig.isEnabled()) {
        context.getSessionHandler().setSessionManager(
            new SessionManager(sessionStoreFactory.createSessionStores(sessionsConfig)));
      } else {
        context.getSessionHandler().setSessionManager(new StubSessionManager());
      }
      context.start();

      // Pass the AppVersion on to any of our servlets (e.g. ResourceFileServlet).
      //
      // This needs to happen after the start() call for some reason.
      context.getServletContext().setAttribute(JettyConstants.APP_VERSION_CONTEXT_ATTR, appVersion);

      // Check to see if servlet filter initialization failed.
      if ((Boolean) UNAVAILABLE_FIELD.get(context)) {
        throw new UnavailableException("Initialization failed.");
      }

      return context;
    } catch (ServletException ex) {
      log.log(Level.WARNING, "Exception adding " + appVersionKey, ex);
      throw ex;
    } catch (Exception ex) {
      log.log(Level.WARNING, "Exception adding " + appVersionKey, ex);
      throw new ServletException(ex);
    }
  }

  /**
   * Forward the specified request on to the {@link Handler}
   * associated with its application version.
   */
  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    AppVersionKey appVersionKey =
        (AppVersionKey) request.getAttribute(JettyConstants.APP_VERSION_KEY_REQUEST_ATTR);
    if (appVersionKey == null) {
      throw new ServletException("Request did not provide an application version");
    }

    Handler handler = getHandler(appVersionKey);
    if (handler == null) {
      // If we throw an exception here it'll get caught, logged, and
      // turned into a 500, which is definitely not what we want.
      // Instead, we check for this before calling handle(), so this
      // should never happen.
      throw new ServletException("Unknown application: " + appVersionKey);
    }

    try {
      handler.handle(target, baseRequest, request, response);
    } catch (Exception ex) {
      throw new ServletException(ex);
    }
  }

  protected void doStart() throws Exception {
    for (Handler handler : getHandlers()) {
      handler.start();
    }

    super.doStart();
  }

  protected void doStop() throws Exception {
    super.doStop();

    for (Handler handler : getHandlers()) {
      handler.stop();
    }
  }

  public void setServer(Server server) {
    super.setServer(server);

    for (Handler handler : getHandlers()) {
      handler.setServer(server);
    }
  }

  public Handler[] getHandlers() {
    return handlerMap.values().toArray(new Handler[0]);
  }

  /**
   * Not supported.
   *
   * @throws UnsupportedOperationException
   */
  public void setHandlers(Handler[] handlers) {
    throw new UnsupportedOperationException();
  }

  /**
   * Not supported.
   *
   * @throws UnsupportedOperationException
   */
  public void addHandler(Handler handler) {
    throw new UnsupportedOperationException();
  }

  /**
   * Not supported.
   *
   * @throws UnsupportedOperationException
   */
  public void removeHandler(Handler handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void expandChildren(List<Handler> list, Class<?> byClass) {
    for (Handler handler : getHandlers()) {
      expandHandler(handler, list, byClass);
    }
  }

  private String getServerInfo() {
    return serverInfo;
  }

  /**
   * {@code NullErrorHandler} does nothing when an error occurs.  The
   * exception is already stored in an attribute of {@code request},
   * but we don't do any rendering of it into the response, UNLESS
   * the webapp has a designated error page (servlet, jsp, or static html)
   * for the current error condition (exception type or error code).
   */
  private static class NullErrorHandler extends ErrorPageErrorHandler {

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {

      log.fine("Custom Jetty ErrorHandler received an error notification.");
      mayHandleByErrorPage(request, response);
      baseRequest.setHandled(true);
    }

    /**
     * Try to invoke a custom error page if a handler is available.
     * If not, render a simple HTML response for {@link
     * HttpServletResponse#sendError} calls, but do nothing for
     * unhandled exceptions.
     *
     * <p>This is loosely based on {@link
     * ErrorPageErrorHandler#handle} but has been modified to add a
     * fallback simple HTML response (because Jetty's default response
     * is not satisfactory) and to set a special {@code
     * ERROR_PAGE_HANDLED} attribute that disables our default
     * behavior of returning the exception to the appserver for
     * rendering.
     */
    private void mayHandleByErrorPage(HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
      // Extract some error handling info from Jetty's proprietary attributes.
      Class exClass = (Class) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE);
      Integer code = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
      String message = (String) request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
      Throwable th = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

      // Now try to find an error handler...
      String error_page=getErrorPage(request);

      // If we found an error handler, dispatch to it.
      if (error_page != null) {
        // Check for reentry into the same error page.
        String old_error_page = (String) request.getAttribute(WebAppContext.ERROR_PAGE);
        if (old_error_page == null || !old_error_page.equals(error_page)) {
          request.setAttribute(WebAppContext.ERROR_PAGE, error_page);
          Dispatcher dispatcher = (Dispatcher) _servletContext.getRequestDispatcher(error_page);
          try {
            if (dispatcher != null) {
              dispatcher.error(request, response);
              // Set this special attribute iff the dispatch actually works!
              // We use this attribute to decide if we want to keep the response content
              // or let the Runtime generate the default error page
              // TODO(wenboz): an invalid html dispatch (404) will mask the exception
              request.setAttribute(ERROR_PAGE_HANDLED, error_page);
              return;
            } else {
              log.warning("No error page " + error_page);
            }
          }
          catch (ServletException e) {
            log.log(Level.WARNING, "Failed to handle error page.", e);
          }
        }
      }

      // If we got an error code but not an exception (e.g. this is a
      // call to HttpServletResponse#sendError), then render our own
      // HTML.  XFE has logic to do this, but the PFE only invokes it
      // for error conditions that it or the AppServer detect.
      if (exClass == null && code != null && message != null) {
        // This template is based on the default XFE error response.
        response.setContentType("text/html; charset=UTF-8");

        PrintWriter writer = response.getWriter();
        
        writer.println("<html><head>");
        writer.println("<meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\">");
        
        writer.println("<title>" + code + " ");
        write(writer,message);
        writer.println("</title>");
        writer.println("</head>");
        writer.println("<body text=#000000 bgcolor=#ffffff>");
        writer.println("<h1>Error: ");
        write(writer,message);
        writer.println("</h1>");
        writer.println("</body></html>");
      }

      // If we got this far and *did* have an exception, it will be
      // retrieved and thrown at the end of
      // JettyServletEngineAdapter#serviceRequest.
    }
  }
}
