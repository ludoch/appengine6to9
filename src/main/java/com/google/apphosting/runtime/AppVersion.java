
package com.google.apphosting.runtime;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.AppinfoPb.AppInfo;
import com.google.apphosting.base.ClonePb.CloneSettings;
import com.google.apphosting.runtime.security.ApplicationEnvironment;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.net.URLClassLoader;
import java.util.Set;


public class AppVersion {
  /**
   * We assume that this string is prepended to the path for any
   * blobs.  We also assume that there is a fallthrough handler that
   * tries to serve all requests as __static__/\1.  This is consistent
   * with the way appcfg_java.py creates app.cfg files.
   */
  private static final String STATIC_PREFIX = "__static__/";

  private final AppVersionKey appVersionKey;
  private final String authDomain;
  private final File rootDirectory;
  private final URLClassLoader classLoader;
  private final ApplicationEnvironment environment;
  private final Set<String> resourceFiles;
  private final Set<String> staticFiles;
  private final SessionsConfig sessionsConfig;
  private final String publicRoot;
  private final CloneSettings cloneSettings;
  private final ThreadGroupPool threadGroupPool;

  public AppVersion(AppVersionKey appVersionKey, AppInfo appInfo, File rootDirectory,
      URLClassLoader classLoader, ApplicationEnvironment environment,
      SessionsConfig sessionsConfig, String publicRoot, CloneSettings cloneSettings,
      ThreadGroupPool threadGroupPool) {
    this.appVersionKey = appVersionKey;
    this.authDomain = appInfo.getAuthDomain();
    this.rootDirectory = rootDirectory;
    this.classLoader = classLoader;
    this.environment = environment;
    this.resourceFiles = null;////extractResourceFiles(appInfo);
    this.staticFiles = null;////extractStaticFiles(appInfo);
    this.sessionsConfig = sessionsConfig;
    if (publicRoot.length() > 0) {
      publicRoot = publicRoot.substring(1) + "/";
    }
    this.publicRoot = publicRoot;
    this.cloneSettings = cloneSettings;
    this.threadGroupPool = threadGroupPool;
  }

  /**
   * Returns the {@link AppVersionKey} that can be used as an
   * identifier for this {@link AppVersion}.
   */
  public AppVersionKey getKey() {
    return appVersionKey;
  }

  /**
   * Returns the top-level directory under which all application
   * version resource files are made available.
   */
  public File getRootDirectory() {
    return rootDirectory;
  }

  /**
   * Returns the custom {@link ClassLoader} that will safely load
   * classes and resource files that were published along with this
   * application version.
   */
  public URLClassLoader getClassLoader() {
    return classLoader;
  }

  /**
   * Returns a {@link CloneSettings} containing information about the current
   * server instance.
   */
  @VisibleForTesting
  public CloneSettings getCloneSettings() {
    return cloneSettings;
  }

  /**
   * Returns the authorization domain of this application version.
   * This is retrieved from the underlying {@link AppInfo}, and is
   * ultimately set from the {@code GlobalConfig} that was used to
   * publish the application.
   */
  public String getAuthDomain() {
    return authDomain;
  }

  /**
   * Returns the environment which was configured for the application.
   */
  public ApplicationEnvironment getEnvironment() {
    return environment;
  }

  public SessionsConfig getSessionsConfig() {
    return sessionsConfig;
  }

  /**
   * Returns true if {@code path} is a resource file that was uploaded
   * as part of this application.
   */
  public boolean isResourceFile(String path) {
    return resourceFiles.contains(publicRoot + path);
  }

  /**
   * Returns true if {@code path} is a static file that was uploaded
   * to BlobStore for use by this application.
   */
  public boolean isStaticFile(String path) {
    return staticFiles.contains(STATIC_PREFIX + publicRoot + path);
  }

  /**
   * Returns the parent directory for all static and resource files.
   */
  public String getPublicRoot() {
    return publicRoot;
  }

  /**
   * Returns the {@link ThreadGroup} that contains all threads owned
   * by this application version.
   */
  public ThreadGroup getRootThreadGroup() {
    return environment.getThreadGroup();
  }

  public ThreadGroupPool getThreadGroupPool() {
    return threadGroupPool;
  }

//////  private Set<String> extractResourceFiles(AppInfo appInfo) {
//////    Set<String> files = new HashSet<String>(appInfo.files().size());
//////    for (AppInfo.File file : appInfo.files()) {
//////      files.add(file.getPath());
//////    }
//////    return files;
//////  }
//////
//////  private Set<String> extractStaticFiles(AppInfo appInfo) {
//////    Set<String> files = new HashSet<String>(appInfo.blobs().size());
//////    for (AppInfo.Blob blob : appInfo.blobs()) {
//////      files.add(blob.getPath());
//////    }
//////    return files;
//////  }
}
