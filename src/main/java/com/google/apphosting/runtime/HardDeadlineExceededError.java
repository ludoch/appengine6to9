
package com.google.apphosting.runtime;


public class HardDeadlineExceededError extends UncatchableError {
  public HardDeadlineExceededError() {
    super();
  }

  public HardDeadlineExceededError(String message) {
    super(message);
  }
}
