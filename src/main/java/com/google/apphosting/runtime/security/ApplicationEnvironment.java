
package com.google.apphosting.runtime.security;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The process environment for an application. Under typical circumstances, a JVM
 * normally has one process environment, but under Prometheus, we configure each
 * application with their own isolated environment.
 *
 */
public class ApplicationEnvironment {

  private final String appId;
  private final String versionId;
  private final InputStream in;
  private final OutputStream out;
  private final OutputStream err;
  private final Map<String, String> systemProperties;
  private final Map<String, String> environmentVariables;
  private final File rootDirectory;
  private final String rootPath;
  private final ThreadGroup threadGroup;
  private final boolean userThreadsEnabled;

  /**
   * All the files and directories uploaded with the application. The
   * value is true for directories and false for files.
   */
  private final Map<String, Boolean> filesAndDirectories;
  private final Permissions userPermissions;
  private final long modifiedTime;

  /**
   * This list of properties we copy directly into the user's local
   * environment, without modification.
   */
  private static final String[] VISIBLE_PROPERTIES = new String[] {
      "file.encoding",
  };

  private static final String[] INVISIBLE_PROPERTIES = new String[] {
      // Needed by some JRE code that expects to receive relative paths.
 
  };

  private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile(
      "^([0-9]+(\\.[0-9]+)*).*$");

  private RuntimeConfiguration runtimeConfiguration;


  /**
   * Contains configuration parameters for the Java runtime which are relative
   * to the specific application denoted by the ApplicationEnvironment.
   */
  public static class RuntimeConfiguration {
    private int verifierMemberCacheSize;
    private boolean classDumpingEnabled;
    private boolean cloudSqlJdbcConnectivityEnabled;

    public RuntimeConfiguration() {
    }

    public RuntimeConfiguration(RuntimeConfiguration config) {
      verifierMemberCacheSize = config.verifierMemberCacheSize;
      classDumpingEnabled = config.classDumpingEnabled;
      cloudSqlJdbcConnectivityEnabled = config.cloudSqlJdbcConnectivityEnabled;
    }
    public boolean isClassDumpingEnabled() {
      return classDumpingEnabled;
    }

    public void setClassDumpingEnabled(boolean classDumpingEnabled) {
      this.classDumpingEnabled = classDumpingEnabled;
    }

    public int getVerifierMemberCacheSize() {
      return verifierMemberCacheSize;
    }

    public void setVerifierMemberCacheSize(int verifierMemberCacheSize) {
      this.verifierMemberCacheSize = verifierMemberCacheSize;
    }

    public void setCloudSqlJdbcConnectivityEnabled(boolean cloudSqlJdbcConnectivityEnabled) {
      this.cloudSqlJdbcConnectivityEnabled = cloudSqlJdbcConnectivityEnabled;
    }

    public boolean getCloudSqlJdbcConnectivityEnabled() {
      return cloudSqlJdbcConnectivityEnabled;
    }
  }

  /**
   * Returns the full set of system properties that user code should
   * be able to read.
   */
  public static Collection<String> getUserReadableProperties() {
    Collection<String> props = new ArrayList<String>(VISIBLE_PROPERTIES.length +
                                                     INVISIBLE_PROPERTIES.length);
    Collections.addAll(props, VISIBLE_PROPERTIES);
    Collections.addAll(props, INVISIBLE_PROPERTIES);
    return props;
  }

  /**
   * Creates a new ApplicationEnvironment for an application.
   *
   * @param appId the application id
   * @param versionId the version id of the application
   * @param in System.in
   * @param out System.out
   * @param err System.err
   * @param extraSystemProperties system properties above and beyond the
   * default whitelisted system properties.
   * @param environmentVariables System.getenv
   * @param rootDirectory root directory for the app
   * @param userPermissions permissions that we pretend to grant to user code
   * @param configuration the runtime configuration for the application
   */
  public ApplicationEnvironment(String appId, String versionId, InputStream in, OutputStream out,
      OutputStream err, Map<String, String> extraSystemProperties,
      Map<String, String> environmentVariables, Map<String, Boolean> filesAndDirectories,
      File rootDirectory, Permissions userPermissions, RuntimeConfiguration configuration,
      long modifiedTime, boolean userThreadsEnabled, ThreadGroup threadGroup) {
    this.appId = appId;
    this.versionId = versionId;
    this.in = in;
    this.out = out;
    this.err = err;
    this.systemProperties = new HashMap<String,String>(extraSystemProperties);
    this.rootDirectory = rootDirectory;
    this.rootPath = rootDirectory.getAbsolutePath() + "/";
    this.filesAndDirectories = filesAndDirectories;
    this.modifiedTime = modifiedTime;

    for (String property : VISIBLE_PROPERTIES) {
      if (!systemProperties.containsKey(property)) {
        systemProperties.put(property, System.getProperty(property));
      }
    }

    // Hide detailed version (such as patch level) information
    Matcher matcher = JAVA_VERSION_PATTERN.matcher(System.getProperty("java.version"));
    String javaVersion;
    if (matcher.matches()) {
      javaVersion = matcher.group(1);
    } else {
      javaVersion = "Unknown";
    }

    systemProperties.put("java.version", javaVersion);
    systemProperties.put("java.vm.version", javaVersion);

    // Tell users what they should expect their current directory to
    // be.  This is different from the real user.dir system property,
    // but our java.io.File override will ensures that everything will
    // behave as if it were.
    systemProperties.put("user.dir", rootDirectory.getPath());

    this.environmentVariables = new HashMap<String,String>(environmentVariables);
    this.userPermissions = userPermissions;
    this.runtimeConfiguration = configuration;
    this.userThreadsEnabled = userThreadsEnabled;
    this.threadGroup = threadGroup;
  }

  public String getAppId() {
    return appId;
  }

  public String getVersionId() {
    return versionId;
  }

  public RuntimeConfiguration getRuntimeConfiguration() {
    return runtimeConfiguration;
  }

  public InputStream getIn() {
    return in;
  }

  public OutputStream getOut() {
    return out;
  }

  public OutputStream getErr() {
    return err;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  public Map<String, String> getEnvironmentVariables() {
    return environmentVariables;
  }

  public File getRootDirectory() {
    return rootDirectory;
  }

  /**
   * Returns a {@link Permissions} object containing {@link
   * java.security.UnresolvedPermission} instances for every
   * permission that was requested by the user in their {@code
   * appengine-web.xml} file.
   *
   * <p>Note that we will <b>not</b> be granting these permissions
   * directly to user code.  Instead, user code is rewritten so that
   * {@link AccessController.checkPermission} passes when given one of
   * these permissions.
   */
  public Permissions getUserPermissions() {
    return userPermissions;
  }

  public boolean userThreadsEnabled() {
    return userThreadsEnabled;
  }

  /**
   * Returns the root {@link ThreadGroup} for this application
   * version.  All background Threads and ThreadGroups started in this
   * environment will be children of this ThreadGroup.
   */
  public ThreadGroup getThreadGroup() {
    return threadGroup;
  }

  private String cleanPath(String path) {
    try {
      File file = new File(path);
      return file.getCanonicalPath();
    } catch (IOException ex) {
      return null;
    }
  }

  /**
   * Returns whether absolutePath is a file uploaded with the application.
   * Returns null if absolutePath is outside the application root directory.
   */
  public Boolean isFile(String absolutePath) {
    if (filesAndDirectories == null) {
      return null;
    }
    absolutePath = cleanPath(absolutePath);
    if (rootDirectory.getAbsolutePath().equals(absolutePath) ||
        rootPath.equals(absolutePath)) {
      return false;
    }
    if (absolutePath == null || !absolutePath.startsWith(rootPath)) {
      return null;
    }
    String relativePath = absolutePath.substring(rootPath.length());
    Boolean isDir = filesAndDirectories.get(relativePath);
    return isDir != null && !isDir.booleanValue();
  }

  /**
   * Returns whether absolutePath is a directory uploaded with the application.
   * Returns null if absolutePath is outside the application root directory.
   */
  public Boolean isDirectory(String absolutePath) {
    if (filesAndDirectories == null) {
      return null;
    }
    absolutePath = cleanPath(absolutePath);
    if (rootDirectory.getAbsolutePath().equals(absolutePath) ||
        rootPath.equals(absolutePath)) {
      return true;
    }
    if (absolutePath == null || !absolutePath.startsWith(rootPath)) {
      return null;
    }
    String relativePath = absolutePath.substring(rootPath.length());
    Boolean isDir = filesAndDirectories.get(relativePath);
    return isDir != null && isDir.booleanValue();
  }

  /**
   * Returns whether absolutePath is a file or directory uploaded with the application.
   * Returns null if absolutePath is outside the application root directory.
   */
  public Boolean isFileOrDirectory(String absolutePath) {
    if (filesAndDirectories == null) {
      return null;
    }
    absolutePath = cleanPath(absolutePath);
    if (rootDirectory.getAbsolutePath().equals(absolutePath) ||
        rootPath.equals(absolutePath)) {
      return true;
    }
    if (absolutePath == null || !absolutePath.startsWith(rootPath)) {
      return null;
    }
    String relativePath = absolutePath.substring(rootPath.length());
    return filesAndDirectories.get(relativePath) != null;
  }

  public Long getModifiedTime(String absolutePath) {
    if (filesAndDirectories == null) {
      return null;
    }
    absolutePath = cleanPath(absolutePath);
    if (absolutePath == null || !absolutePath.startsWith(rootPath)) {
      return null;
    }
    String relativePath = absolutePath.substring(rootPath.length());
    if (filesAndDirectories.get(relativePath) != null) {
      return modifiedTime;
    }
    return null;
  }
  

}
