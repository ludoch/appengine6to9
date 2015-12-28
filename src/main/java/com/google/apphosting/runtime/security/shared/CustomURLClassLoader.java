
package com.google.apphosting.runtime.security.shared;

import java.net.URL;
import java.net.URLClassLoader;



public class CustomURLClassLoader extends URLClassLoader {


  public CustomURLClassLoader(URL[] urls, ClassLoader parent) {

      super(urls, parent);
  }


}
