
package com.google.apphosting.utils.jetty;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.HashSessionIdManager;

import sun.reflect.Reflection;

/**
 * A stub session that doesn't actually allow attributes to be set, and thus
 * doesn't need time to save them, but satisifies JSP's eager use of
 * {@link HttpServletRequest#getSession()} (assuming the JSP code doesn't then
 * want a real session).
 *
 * We're not actually nulling out sessions; there should be enough to
 * allow a JSP to think sessions are there for any bookkeeping the
 * engine might want to do.  So this sets JSESSIONID, sends a cookie
 * to the user, and even allows enumaration of the attributes map and
 * looking up individual attributes.  However, an exception is thrown
 * if you try to make any mutations to the session.
 *
 *
 */
public class StubSessionManager extends AbstractSessionManager {

 

  /**
   * The actual session object for a stub session.  It will allow "generic"
   * operations including getAttributeNames(), but fails on any get/set/remove
   * of a specific attribute.
   */
  public class StubSession extends AbstractSession {

    protected StubSession(String id) {
      super(StubSessionManager.this, System.currentTimeMillis(), System.currentTimeMillis(), id);
    }

    public StubSession(HttpServletRequest req) {
      super(StubSessionManager.this, req);
    }


    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void removeAttribute(String name) {
      throwException();
    }

    @Override
    public void setAttribute(String name, Object value) {
      throwException();
    }

    private void throwException() {
      // We're caller 1.  Caller 2 is setAttribute or removeAttribute.
      // We want their immediate caller (3).
      if (Reflection.getCallerClass(3).getName().startsWith("org.apache.jasper")) {
        // Jasper 6.0.59 tries to remove attributes from a session to
        // make way for new variables.  No sense disallowing this --
        // the user can't really do anything about it.
        return;
      }
      throw new RuntimeException("Session support is not enabled in appengine-web.xml.  "
        + "To enable sessions, put <sessions-enabled>true</sessions-enabled> in that "
        + "file.  Without it, getSession() is allowed, but manipulation of session"
        + "attributes is not.");
    }
  }


  public StubSessionManager() {
    this.setSessionIdManager(new HashSessionIdManager());
  }
  
  @Override
  protected void addSession(AbstractSession session) {
    // do nothing; we'll make a new one if we need it
  }

  /**
   * Gets an "existing" StubSession.  Since they can't have any data, and may
   * have been created by some other clone, we trust that it really existed and
   * just return a new session.
   * 
   * @param id the id of the requested session
   * @return a new StubSession of the correct id
   */
  @Override
  public AbstractSession getSession(String id) {
    return new StubSession(id);
  }


  @Override
  public int getSessions() {
    return 0;
  }


  @Override
  protected AbstractSession newSession(HttpServletRequest req) {
    return new StubSession(req);
  }

  @Override
  protected boolean removeSession(String arg0) {
    // since we don't save them anyway, this is a no-op
    return true;
  }

  @Override
  public boolean isUsingCookies() {
    return false;
  }
  
  
  @Override
  public void renewSessionId(String oldClusterId, String oldNodeId,
                             String newClusterId, String newNodeId) {
   //janb: the id should be changed on the existing session but this won't be possible
  }
  
  @Override
  protected void shutdownSessions() throws Exception {
   //do nothing
    
  }
}
