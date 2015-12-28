
package com.google.apphosting.runtime.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class WhiteList {

  private WhiteList() {
  }

  private static Set<String> whiteList = new HashSet<String>( Arrays.asList(
      "java.beans.Transient"
          //and more.......

      ) );

  public static Set<String> getWhiteList() {
    return whiteList;
  }
}
