package com.google.apphosting.runtime.security;


import java.net.URL;
import java.net.URLClassLoader;


public final class UserClassLoader extends URLClassLoader {

  public UserClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  public UserClassLoader() {
    super (null,null);
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }


}
