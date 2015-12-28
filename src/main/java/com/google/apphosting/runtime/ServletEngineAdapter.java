
package com.google.apphosting.runtime;

import com.google.apphosting.base.RuntimePb.UPRequest;
import com.google.apphosting.base.RuntimePb.UPResponse;

import java.io.FileNotFoundException;
import java.io.IOException;

import javax.servlet.ServletException;

/**
 * This interface abstracts away the details of starting up and
 * shutting down a servlet engine, as well as adapting between the
 * concrete classes that implement the Java Servlet API and the
 * Prometheus Untrusted Process API.
 *
 */
public interface ServletEngineAdapter {
  /**
   * Perform whatever setup is necessary for this servlet container.
   * This method should return once the appropriate setup has been
   * completed.
   *
   * @param serverInfo The string that should be returned by {@code
   * ServletContext.getServerInfo()}.
   */
  public void start(String serverInfo);

  /**
   * Perform any shutdown procedures necessary for this servlet
   * container.  This method should return once the shutdown has
   * been completed.
   */
  public void stop();

  /**
   * Register the specified application version for future calls to
   * {@code serviceRequest}.
   *
   * @throws FileNotFoundException If any of the specified files could
   * not be located.
   */
  public void addAppVersion(AppVersion appVersion) throws FileNotFoundException;

  /**
   * Remove the specified application version and free up any
   * resources associated with it.
   */
  public void deleteAppVersion(AppVersion appVersion);

  /**
   * Executes the HTTP request spceified by {@code upRequest} and
   * writes the response to {@code upResponse}.
   *
   * <p>This will involve finding and possibly instantiating and
   * initializing the appropriate servlet, setting up the servlet
   * environment from the request protocol buffer, invoking the
   * servlet, and copying the response into {@code upResponse}.
   *
   * @throws IOException If any error related to the request buffer
   * was detected.
   */
  public void serviceRequest(UPRequest upRequest, UPResponse upResponse)
      throws ServletException, IOException;

  /**
   * Sets the {@link SessionStoreFactory} that will be used to create the list
   * of {@link SessionStore SessionStores} to which the HTTP Session will be
   * stored, if sessions are enabled. This method must be invoked after
   * {@link #start(String)}.
   */
  public void setSessionStoreFactory(SessionStoreFactory factory);

}
