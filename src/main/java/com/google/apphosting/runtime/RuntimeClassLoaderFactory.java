
package com.google.apphosting.runtime;

import com.google.apphosting.runtime.security.ClassFilter;
import com.google.apphosting.runtime.security.PermissionFactory;
import com.google.apphosting.runtime.security.RuntimeClassLoader;


import java.io.File;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;


public class RuntimeClassLoaderFactory {

  private static final Logger log = Logger.getLogger(RuntimeClassLoaderFactory.class.getName());

  private PermissionFactory runtimePermissionFactory;
  private ClassFilter jreClassFilter;
  private ClassFilter systemClassLoaderClassFilter;

  public RuntimeClassLoaderFactory() {
    jreClassFilter = new RuntimeJreClassFilter();
    systemClassLoaderClassFilter = new EmptyClassFilter();
  }


  public void setRuntimePermissionFactory(PermissionFactory runtimePermissionFactory) {
    this.runtimePermissionFactory = runtimePermissionFactory;
  }


  public void setJreClassFilter(ClassFilter jreClassFilter) {
    this.jreClassFilter = jreClassFilter;
  }

  public void setSystemClassLoaderClassFilter(ClassFilter systemClassLoaderClassFilter) {
    this.systemClassLoaderClassFilter = systemClassLoaderClassFilter;
  }


  public RuntimeClassLoader createFromUrls(ClassPathUtils classPathUtils) {
    if (log.isLoggable(Level.FINE)) {
      log.fine("Creating RuntimeClassLoader with implementation urls, " +
               Arrays.toString(classPathUtils.getRuntimeImplUrls()) + " and shared urls, " +
               Arrays.toString(classPathUtils.getRuntimeSharedUrls()));
    }

    if (runtimePermissionFactory == null) {
      runtimePermissionFactory = new DefaultRuntimePermissionFactory(classPathUtils);
    }

    return new RuntimeClassLoader(classPathUtils,
                                  jreClassFilter,
                                  systemClassLoaderClassFilter,
                                  runtimePermissionFactory);
  }

  public static class DefaultRuntimePermissionFactory implements PermissionFactory {

    private ClassPathUtils classPathUtils;
    private String sharedBufferBase;
    private Collection<File> runtimeProvidedPrecompiledFiles;

    public DefaultRuntimePermissionFactory(ClassPathUtils classPathUtils,
                                           String sharedBufferBase) {
      this.classPathUtils = classPathUtils;
      this.sharedBufferBase = (sharedBufferBase != null ? sharedBufferBase : "/dev/shm");
      runtimeProvidedPrecompiledFiles =
          classPathUtils.getRuntimeProvidedPrecompiledFileMap().values();
    }

    public DefaultRuntimePermissionFactory(ClassPathUtils classPathUtils) {
      this(classPathUtils, null);
    }

    @Override
    public void addPermissions(CodeSource codeSource, PermissionCollection permissions) {

    }


  }

  /**
   * This is the default {@code ClassFilter} for code loaded in the
   * {@code RuntimeClassLoader}.  It currently does not allow
   * references to AWT as an example.
   */
  private static class RuntimeJreClassFilter implements ClassFilter {
    @Override
    public boolean accept(String className) {
      // TODO(tobyr): Not sure what we want to disallow in here.
      return !className.startsWith("java.awt.");
    }
  }

  /**
   * This {@code ClassFilter} does not allow access to any classes.
   */
  private static class EmptyClassFilter implements ClassFilter {
    @Override
    public boolean accept(String className) {
      return false;
    }
  }
}
