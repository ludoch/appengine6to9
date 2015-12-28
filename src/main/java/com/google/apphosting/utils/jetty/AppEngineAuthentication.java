
package com.google.apphosting.utils.jetty;

import com.google.apphosting.api.ApiProxy;
import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.security.Authenticator;
import org.eclipse.jetty.server.security.SecurityHandler;
import org.eclipse.jetty.server.security.UserRealm;

import java.io.IOException;
import java.security.Principal;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code AppEngineAuthentication} is a utility class that can
 * configure a Jetty {@link SecurityHandler} to integrate with the App
 * Engine authentication model.
 *
 * <p>Specifically, it registers a custom {@link Authenticator}
 * instance that knows how to redirect users to a login URL using the
 * {@link UsersService}, and a custom {@link UserRealm} that is aware
 * of the custom roles provided by the App Engine.
 *
 */
class AppEngineAuthentication {
  private static final Logger log = Logger.getLogger(
      AppEngineAuthentication.class.getName());

  /**
   * URLs that begin with this prefix are reserved for internal use by
   * App Engine.  We assume that any URL with this prefix may be part
   * of an authentication flow (as in the Dev Appserver).
   */
  private static final String AUTH_URL_PREFIX = "/_ah/";

  private static final String AUTH_METHOD = "Google Login";

  private static final String AUTH_TYPE = "GOOGLE_AUTH";

  private static final String REALM_NAME = "Google App Engine";

  // Keep in sync with com.google.apphosting.runtime.jetty.JettyServletEngineAdapter.
  private static final String SKIP_ADMIN_CHECK_ATTR =
      "com.google.apphosting.internal.SkipAdminCheck";

  /**
   * Inject custom {@link UserRealm} and {@link Authenticator}
   * implementations into the specified {@link SecurityHandler}.
   */
  public static void configureSecurityHandler(SecurityHandler handler) {
    handler.setAuthenticator(new AppEngineAuthenticator());
    handler.setUserRealm(new AppEngineUserRealm());
  }

  /**
   * {@code AppEngineAuthenticator} is a custom {@link Authenticator}
   * that knows how to redirect the current request to a login URL in
   * order to authenticate the user.
   */
  private static class AppEngineAuthenticator implements Authenticator {
    public Principal authenticate(UserRealm realm,
                                  String pathInContext,
                                  Request request,
                                  Response response) {
      UserService userService = UserServiceFactory.getUserService();

      // Check this before checking if there is a user logged in, so
      // that we can log out properly.  Specifically, watch out for
      // the case where the user logs in, but as a role that isn't
      // allowed to see /*.  They should still be able to log out.
      if (request.getRequestURI().indexOf(AUTH_URL_PREFIX) == 0) {
        log.fine("Got " + request.getRequestURI() + ", returning NOBODY to " +
                 "imply authentication is in progress.");
        return SecurityHandler.__NOBODY;
      }

      if (request.getAttribute(SKIP_ADMIN_CHECK_ATTR) != null) {
        log.info("Returning NOBODY because of SkipAdminCheck.");
        // Warning: returning NOBODY here will bypass security restrictions!
        return SecurityHandler.__NOBODY;
      }

      // If the user is authenticated already, just create a
      // AppEnginePrincipal or AppEngineFederatedPrincipal for them.
      if (userService.isUserLoggedIn()) {
        // Check if the current user is a federated user.
        Principal princpal;
        User user = userService.getCurrentUser();
        log.fine("authenticate() returning new principal for " + user);
        princpal = new AppEnginePrincipal(user);
        request.setUserPrincipal(princpal);
        request.setAuthType(AUTH_TYPE);
        return princpal;
      }

      if (response == null) {
        // We can't do the redirect anyway, so just return null to
        // indicate that no one is logged in.
        //
        // N.B.(schwardo): Jetty makes this call with a null response
        // *after* a request completes, while it's trying to
        // disassociate any user who was authenticated during the
        // previous request!  See SecurityHandler.java:224.  WTF?
        log.fine("Got " + request.getRequestURI() + " with null response, returning null.");
        return null;
      }

      log.info("Got " + request.getRequestURI() + " but no one was logged in, redirecting.");
      try {
        try {
          String url = userService.createLoginURL(getFullURL(request));
          response.sendRedirect(url);
          // Returning null here means that we've already committed a
          // response here and Jetty should not continue to handle the request.
          return null;
        } catch (ApiProxy.ApiProxyException ex) {
          // If we couldn't get a login URL for some reason, return a 403 instead.
          log.log(Level.SEVERE, "Could not get login URL:", ex);
          response.sendError(HttpServletResponse.SC_FORBIDDEN);
          return null;
        }
      } catch (IOException ex) {
        log.log(Level.WARNING, "Got an IOException from sendRedirect:", ex);
        return null;
      }
    }

    public String getAuthMethod() {
      return AUTH_METHOD;
    }
  }

  /**
   * Returns the full URL of the specified request, including any query string.
   */
  private static String getFullURL(HttpServletRequest request) {
    StringBuffer buffer = request.getRequestURL();
    if (request.getQueryString() != null) {
      buffer.append('?');
      buffer.append(request.getQueryString());
    }
    return buffer.toString();
  }

  /**
   * {@code AppEngineUserRealm} is a custom Jetty {@link UserRealm} that
   * is aware of the two special role names implemented by Google App
   * Engine.  Any authenticated user is a member of the {@code "*"}
   * role, and any administrators are members of the {@code "admin"}
   * role.  Any other roles will be logged and ignored.
   */
  private static class AppEngineUserRealm implements UserRealm {
    private static final String USER_ROLE = "*";
    private static final String ADMIN_ROLE = "admin";

    @Override
    public boolean isUserInRole(Principal principal, String role) {
      UserService userService = UserServiceFactory.getUserService();

      log.info("Checking if principal "+ principal + " is in role " + role);

      if (principal == null) {
        log.info("isUserInRole() called with null principal.");
        return false;
      } else if (!(principal instanceof AppEnginePrincipal)) {
        log.info("Got an unexpected principal of type: " + principal.getClass().getName());
        return false;
      }

      User user = ((AppEnginePrincipal) principal).getUser();
      if (USER_ROLE.equals(role)) {
        return true;
      }

      if (ADMIN_ROLE.equals(role)) {
        if (user.equals(userService.getCurrentUser())) {
          return userService.isUserAdmin();
        } else {
          // TODO(schwardo): I'm not sure this will happen in
          // practice.  If it does, we may need to pass an
          // application's admin list down somehow.
          log.warning("Cannot tell if non-logged-in user " + user + " is an admin.");
          return false;
        }
      } else {
        log.warning("Unknown role: " + role + ".");
        return false;
      }
    }

    @Override
    public String getName() {
      return REALM_NAME;
    }

    @Override
    public void disassociate(Principal user) {
      // Jetty calls this on every request -- even if user is null!
      if (user != null) {
        log.fine("Ignoring disassociate call for: " + user);
      }
    }

    // TODO(schwardo): The rest of these methods seem to be unused and
    // it's unclear how they should be implemented.  For now, they all
    // simply throw an UnsupportedOperationException and log
    // judiciously.

    @Override
    public Principal getPrincipal(String username) {
      log.info("getPrincipal(" + username + ") throwing UnsupportedOperationException.");
      throw new UnsupportedOperationException();
    }

    @Override
    public Principal authenticate(String username, Object credentials, Request request) {
      log.info("Authenticate(" + username + ", " + credentials + ") " +
               "throwing UnsupportedOperationException.");
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean reauthenticate(Principal user) {
      log.info("reauthenticate(" + user + ") throwing UnsupportedOperationException.");
      throw new UnsupportedOperationException();
    }

    @Override
    public Principal pushRole(Principal user, String role) {
      log.warning("pushRole throwing an UnsupportedOperationException");
      throw new UnsupportedOperationException();
    }

    @Override
    public Principal popRole(Principal user) {
      log.warning("popRole throwing an UnsupportedOperationException");
      throw new UnsupportedOperationException();
    }

    @Override
    public void logout(Principal user) {
      log.warning("logout(" + user + ") throwing an UnsupportedOperationException");
      throw new UnsupportedOperationException();
    }
  }

  /**
   * {@code AppEnginePrincipal} is an implementation of {@link Principal}
   * that represents a logged-in Google App Engine user.
   */
  public static class AppEnginePrincipal implements Principal {
    private final User user;

    public AppEnginePrincipal(User user) {
      this.user = user;
    }

    public User getUser() {
      return user;
    }

    @Override
    public String getName() {
      if ((user.getFederatedIdentity() != null) && (user.getFederatedIdentity().length() > 0)) {
        return user.getFederatedIdentity();
      } 
      return user.getEmail();
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof AppEnginePrincipal) {
        return user.equals(((AppEnginePrincipal) other).user);
      } else {
        return false;
      }
    }

    @Override
    public String toString() {
      return user.toString();
    }

    @Override
    public int hashCode() {
      return user.hashCode();
    }
  }
}
