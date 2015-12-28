
package com.google.apphosting.runtime.security;

/**
 * Provides a filter over a set of classes. A ClassFilter
 * is used to restrict the set of classes that a ClassLoader will load.
 *
 */
public interface ClassFilter {

  /**
   * Returns true if the class should be accepted for classloading.
   *
   * @param className The not <code>null</code> name of the class,
   * formatted in normal JLS conventions. For example, java.lang.String
   * or Foo$Bar.
   * @return true only if the class should be loaded
   */
  public boolean accept(String className);
}
