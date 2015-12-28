
package com.google.apphosting.runtime;

import com.google.apphosting.runtime.RuntimeClassLoaderFactory.DefaultRuntimePermissionFactory;
import com.google.apphosting.runtime.security.CustomSecurityManager;
import com.google.apphosting.runtime.security.RuntimeClassLoader;

import java.io.FilePermission;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The primary entry point for starting up a {@link JavaRuntime}. This class
 * creates a new {@link RuntimeClassLoader} with an appropriate classpath
 * and launches a new {@code JavaRuntime} in that {@code ClassLoader} via
 * {@link JavaRuntimeFactory}.
 *
 * This class specifically minimizes dependencies on google3 such that they
 * will be loaded within the {@code RuntimeClassLoader} instead of the launching
 * {@code SystemClassLoader}.
 *

 */
public class JavaRuntimeMain {

  private static Logger logger = Logger.getLogger(JavaRuntimeMain.class.getName());

  private String factoryClass;
  private ClassPathUtils classPathUtils;

  public static void main(String args[]) {
    System.setSecurityManager(new CustomSecurityManager());

    String pkg = JavaRuntimeMain.class.getPackage().getName();
    String factoryClass = pkg + ".JavaRuntimeFactory";
    ClassPathUtils classPathUtils = new ClassPathUtils();
    new JavaRuntimeMain(factoryClass, classPathUtils).load(args);
  }

  public JavaRuntimeMain(String factoryClass, ClassPathUtils classPathUtils) {
    this.factoryClass = factoryClass;
    this.classPathUtils = classPathUtils;
  }

  public void load(String[] args) {
    try {
      // Set this property as early as possible, to catch all possible uses of streamz.
      System.setProperty("com.google.appengine.runtime.environment", "Production");
      RuntimeClassLoaderFactory runtimeLoaderFactory = new RuntimeClassLoaderFactory();
      String appsRoot = getApplicationRoot(args);
      String builtinsRoot = getBuiltinsRoot(args);
      String sharedBufferBase = getSharedBufferBase(args);
      runtimeLoaderFactory.setRuntimePermissionFactory(
          new RuntimePermissionFactory(classPathUtils, appsRoot, builtinsRoot, sharedBufferBase));
      RuntimeClassLoader runtimeLoader = runtimeLoaderFactory.createFromUrls(classPathUtils);
      Class<?> runtimeFactory = runtimeLoader.loadClass(factoryClass);
      Method mainMethod = runtimeFactory.getMethod("main", String[].class);
      mainMethod.invoke(null, new Object[]{args});
    } catch (Exception e) {
      String msg = "Unexpected failure creating RuntimeClassLoader";
      logger.log(Level.SEVERE, msg, e);
      throw new RuntimeException(msg, e);
    } 
  }

  /**
   * Parse the value of the --application_root flag.
   */
  private String getApplicationRoot(String[] args) {
    return getFlag(
        args,
        "--application_root",
        "No --application_root flag found, not granting a read FilePermission.");
  }

  /**
   * Parse the value of the --application_root flag.
   */
  private String getBuiltinsRoot(String[] args) {
    return getFlag(
        args,
        "--builtins_root",
        "No --builtins_root flag found, not granting a read FilePermission.");
  }

  /**
   * Parse the value of the --shared_buffer_base flag.
   */
  private String getSharedBufferBase(String[] args) {
    return getFlag(args, "--shared_buffer_base", null);
  }

  /**
   * Parse the value of the given flag.  Unfortunately we
   * cannot rely on the usual flag parsing code because it is loaded
   * in the {@link RuntimeClassLoader} and we need the value of the
   * flag before the {@link RuntimeClassLoader} is created.
   */
  private String getFlag(String[] args, String flagName, String warningMsgIfAbsent) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(flagName)) {
        return args[i+1];
      } else if (args[i].startsWith(flagName + "=")) {
        return args[i].substring((flagName + "=").length());
      }
    }
    if (warningMsgIfAbsent != null) {
      logger.warning(warningMsgIfAbsent);
    }
    return null;
  }

  private class RuntimePermissionFactory extends DefaultRuntimePermissionFactory {

    public RuntimePermissionFactory(ClassPathUtils classPathUtils,
                                    String appsRootDir,
                                    String builtinsRootDir,
                                    String sharedBufferBase) {
      super(classPathUtils, sharedBufferBase);
  }

   
  }
}
