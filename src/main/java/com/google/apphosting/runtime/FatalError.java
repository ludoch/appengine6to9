
package com.google.apphosting.runtime;


public class FatalError extends UncatchableError {
  
  public FatalError(String msg) {
    super(msg);
  }
  
  public FatalError(String msg, Throwable t) {
    super(msg, t);
  }
}
