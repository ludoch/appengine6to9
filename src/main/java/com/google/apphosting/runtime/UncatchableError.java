
package com.google.apphosting.runtime;


public abstract class UncatchableError extends Error {
  
  public UncatchableError() {
    super();
  }
  
  public UncatchableError(String msg) {
    super(msg);    
  }
  
  public UncatchableError(String msg, Throwable t) {
    super(msg, t);
  }
}
