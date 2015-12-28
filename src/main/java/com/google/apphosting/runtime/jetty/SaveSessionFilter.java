
package com.google.apphosting.runtime.jetty;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.google.apphosting.runtime.jetty.SessionManager.AppEngineSession;

/**
 * {@code SaveSessionFilter} flushes a {@link AppEngineSession} to
 * persistent storage after each request completes.
 *
 */
public class SaveSessionFilter implements Filter {

  public void init(FilterConfig config) {
    // No init.
  }

  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) req;

    try {
      chain.doFilter(req, resp);
    } finally {
      HttpSession session = httpReq.getSession(false);
      if (session instanceof AppEngineSession) {
        AppEngineSession aeSession = (AppEngineSession) session; 
        if (aeSession.isDirty()) {
          aeSession.save();
        }
      }
    }
  }

  public void destroy() {
    // No destruction.
  }
}
