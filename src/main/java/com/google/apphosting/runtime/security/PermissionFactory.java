
package com.google.apphosting.runtime.security;

import java.security.PermissionCollection;
import java.security.CodeSource;

public interface PermissionFactory {

  public void addPermissions(CodeSource codeSource, PermissionCollection permissions);


}
