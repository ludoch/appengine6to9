
package com.google.apphosting.runtime.security;

import com.google.apphosting.runtime.ClassPathUtils;

import java.net.URLClassLoader;
import java.net.URL;


public class RuntimeClassLoader extends URLClassLoader {

  public RuntimeClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  public RuntimeClassLoader(ClassPathUtils classPathUtils, ClassFilter jreClassFilter, ClassFilter systemClassLoaderClassFilter, PermissionFactory runtimePermissionFactory) {
    super(null,null);
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  public ClassPathUtils getClassPathUtils() {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  public ClassFilter getDelegateToSystemClassLoaderFilter() {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  public void setDelegateToSystemClassLoaderFilter(ClassFilter classFilter) {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

}
