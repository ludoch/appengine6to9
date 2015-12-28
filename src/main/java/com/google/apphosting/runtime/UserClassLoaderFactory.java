
package com.google.apphosting.runtime;

import com.google.apphosting.runtime.security.ApplicationEnvironment;
import com.google.apphosting.runtime.security.RuntimeClassLoader;
import com.google.apphosting.runtime.security.UserClassLoader;

import java.io.File;
import java.net.URL;
import java.util.logging.Logger;

public class UserClassLoaderFactory {

  private static final transient Logger log = Logger.getLogger(
      UserClassLoaderFactory.class.getName());

  public UserClassLoaderFactory() {
  }

  public UserClassLoader createClassLoader(RuntimeClassLoader runtimeLoader,
      URL[] userUrls, File contextRoot, File[] allFiles, ApplicationEnvironment environment) {

 return new UserClassLoader();
  }


}
