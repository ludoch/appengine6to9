
package com.google.apphosting.runtime.security;


public class SystemFailureException extends Exception {
  public SystemFailureException(String msg, Throwable t) {
    super(msg, t);
  }
}