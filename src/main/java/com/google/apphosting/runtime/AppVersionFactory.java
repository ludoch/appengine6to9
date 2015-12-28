
package com.google.apphosting.runtime;

import com.google.appengine.api.utils.SystemProperty;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.base.AppId;
import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.AppinfoPb.AppInfo;
import com.google.apphosting.base.ClonePb.CloneSettings;
import com.google.apphosting.runtime.security.ApplicationEnvironment;
import com.google.apphosting.runtime.security.ApplicationEnvironment.RuntimeConfiguration;
import com.google.apphosting.runtime.security.EmptyInputStream;
import com.google.apphosting.runtime.security.LogStream;
import com.google.apphosting.runtime.security.RuntimeClassLoader;
import com.google.apphosting.runtime.security.UserClassLoader;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.AppEngineWebXmlReader;
import com.google.apphosting.utils.config.ClassPathBuilder;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Permissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AppVersionFactory {
  private static final Logger log =
      Logger.getLogger(AppVersionFactory.class.getName());



  /**
   * The root directory for application versions.  All other paths are
   * relative to this directory.
   */
  private final File sharedDirectory;

  /**
   * The root directory for builtin application versions.
   */
  private final File builtinsDirectory;

  /**
   * An optional {@link CloneSettings} protocol buffer that was passed
   * down from the appserver.
   */
  private CloneSettings cloneSettings;


  private String runtimeVersion;


  /**
   * Construct a new {@code AppVersionFactory}.
   *
   * @param sharedDirectory The root directory where all application
   * versions are persisted.
   *
   * @param builtinsDirectory The root directory where all builtin
   * versions are persisted.
   *
   * @param securityTrustedAppIds A list of app-ids that are considered
   * "trusted" and allowed to perform dangerous operations.
   *
   * @param runtimeVersion The runtime version which is reported to users.
   */
  public AppVersionFactory(
      File sharedDirectory,
      File builtinsDirectory,
      Set<String> securityTrustedAppIds,
      String runtimeVersion) {
    this.sharedDirectory = sharedDirectory;
    this.builtinsDirectory = builtinsDirectory;
    this.runtimeVersion = runtimeVersion;
  }

  /**
   * Sets the {@link CloneSettings} that will be used for any future
   * {@link #createAppVersion} calls.
   */
  public void setCloneSettings(CloneSettings cloneSettings) {
    this.cloneSettings = cloneSettings;
  }

  /**
   * Create an {@code AppVersion} from the specified {@code AppInfo}
   * protocol buffer.
   *
   * @param appInfo The application configuration.
   * @param configuration The runtime configuration for the application.
   *
   * @throws FileNotFoundException If any of the specified files
   * cannot be found.
   */
  public AppVersion createAppVersion(AppInfo appInfo, RuntimeConfiguration configuration)
      throws FileNotFoundException {
    AppVersionKey appVersionKey = AppVersionKey.fromAppInfo(appInfo);

    File builtinDirectory = getBuiltinDirectory(appVersionKey);
    File rootDirectory;
    Map<String, Boolean> filesAndDirectories;
    if (builtinDirectory != null) {
      // Overriding app files with builtin files.
      if (!fileExists(builtinDirectory)) {
        throw new FileNotFoundException("Builtin not found: " + builtinDirectory);
      }
      rootDirectory = builtinDirectory;
      filesAndDirectories = null;  // Causes checks to fall back on normal file system.
    } else {
      rootDirectory = getRootDirectory(appVersionKey);
      filesAndDirectories = extractFilesAndDirectories(appInfo);
    }

    AppEngineWebXmlReader reader = new AppEngineWebXmlReader(rootDirectory.getPath()) {
      @Override
      protected boolean allowMissingThreadsafeElement() {
        // There are many apps deployed in production that don't have a threadsafe
        // element, so to avoid breaking apps we allow the missing element.
        return true;
      }
    };
    AppEngineWebXml appEngineWebXml = reader.readAppEngineWebXml();
    Map<String, String> sysProps = createSystemProperties(appEngineWebXml, appInfo);
    Map<String, String> envVars = appEngineWebXml.getEnvironmentVariables();
    Permissions userPermissions = appEngineWebXml.getUserPermissions();
    boolean threadsEnabled = false;
    if (cloneSettings != null) {
      threadsEnabled = cloneSettings.isBackgroundThreadsEnabled();
    }
    ThreadGroup rootThreadGroup = new ThreadGroup("App Engine: " + appVersionKey);
    ApplicationEnvironment environment = new ApplicationEnvironment(appInfo.getAppId(),
        appInfo.getVersionId(), EmptyInputStream.STREAM, new LogStream(Level.INFO),
        new LogStream(Level.WARNING), sysProps, envVars, filesAndDirectories, rootDirectory,
        userPermissions, configuration, System.currentTimeMillis(), threadsEnabled,
        rootThreadGroup);

    NetworkServiceDiverter.divertUrlStreamHandler(appEngineWebXml.getUrlStreamHandlerType());

    UserClassLoader classLoader =
        createClassLoader(environment, rootDirectory, appInfo, appEngineWebXml);
    SessionsConfig sessionsConfig = new SessionsConfig(appEngineWebXml.getSessionsEnabled(),
        appEngineWebXml.getAsyncSessionPersistence(),
        appEngineWebXml.getAsyncSessionPersistenceQueueName());
    ThreadGroupPool threadGroupPool = new ThreadGroupPool(
        rootThreadGroup,
        "Request #",
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread th, Throwable ex) {
            log.log(Level.WARNING, "Uncaught exception from " + th, ex);
          }
      });
    return new AppVersion(appVersionKey, appInfo, rootDirectory, classLoader, environment,
                          sessionsConfig, appEngineWebXml.getPublicRoot(), cloneSettings,
                          threadGroupPool);
  }

  /**
   * Creates a new {@code AppVersion} with a default RuntimeConfiguration.
   * @param appInfo The application configuration.
   * @throws FileNotFoundException
   */
  public AppVersion createAppVersion(AppInfo appInfo)
      throws FileNotFoundException {
    return createAppVersion(appInfo, new RuntimeConfiguration());
  }

  /**
   * @return The directory for the given builtin or {@code null} if appVersionKey does not refer to
   * a builtin.
   */
  private File getBuiltinDirectory(AppVersionKey appVersionKey) {

    return new File(builtinsDirectory, "aaaaaludo");
  }

  /**
   * Return the top-level directory for the specified application
   * version.  The directory returned will be an absolute path beneath
   * {@code sharedDirectory}.
   *
   * @throws FileNotFoundException If the directory that would be
   * returned does not exist.
   */
  private File getRootDirectory(AppVersionKey appVersionKey) throws FileNotFoundException {

    File root =  new File(new File(sharedDirectory, appVersionKey.getAppId()),
        appVersionKey.getVersionId());


    if (!fileExists(root)) {
      throw new FileNotFoundException(root.toString());
    }
    return root.getAbsoluteFile();
  }

  /**
   * Creates the system properties that will be seen by the user
   * application.  This is a combination of properties that they've
   * requested (via {@code appengine-web.xml}), information about the
   * runtime, information about the application, and information about
   * this particular JVM instance.
   */
  private Map<String, String> createSystemProperties(AppEngineWebXml appEngineWebXml,
                                                     AppInfo appInfo) {
    Map<String,String> props = new HashMap<String,String>();
    props.putAll(appEngineWebXml.getSystemProperties());
    props.put(SystemProperty.environment.key(),
              SystemProperty.Environment.Value.Production.value());
    props.put(SystemProperty.version.key(), runtimeVersion);
    AppId appId = AppId.parse(appInfo.getAppId());
    props.put(SystemProperty.applicationId.key(), appId.getLongAppId());
    props.put(SystemProperty.applicationVersion.key(), appInfo.getVersionId());

    return props;
  }

  /**
   * Create a {@link UserClassLoader} that loads resources from the
   * application version specified in {@code appInfo}.
   *
   * @throws FileNotFoundException If any files specified in {@code
   * appInfo} do not exist on the filesystem.
   */
  private UserClassLoader createClassLoader(ApplicationEnvironment environment, File root,
      AppInfo appInfo, AppEngineWebXml appEngineWebXml)
          throws FileNotFoundException {
    RuntimeClassLoader runtimeClassLoader = getRuntimeClassLoader();
    ClassPathUtils classPathUtils = runtimeClassLoader.getClassPathUtils();

    ClassPathBuilder classPathBuilder =
        new ClassPathBuilder(appEngineWebXml.getClassLoaderConfig());
    Set<File> allFiles = new HashSet<File>();

    // From the servlet spec, SRV.9.5 "The Web application class loader must load
    // classes from the WEB-INF/ classes directory first, and then from library JARs
    // in the WEB-INF/lib directory."
    try {
      File classes = new File(new File(root, "WEB-INF"), "classes");
      if (fileExists(classes)) {
        classPathBuilder.addClassesUrl(classes.toURI().toURL());
      }
    } catch (MalformedURLException ex) {
      log.log(Level.WARNING, "Could not add WEB-INF/classes", ex);
    }


    for (AppInfo.File appFile : appInfo.files()) {
      File file = new File(root, appFile.getPath());
      allFiles.add(file);
      if (appFile.getPath().startsWith("WEB-INF/lib/")) {
        try {
          classPathBuilder.addAppJar(new URL("file", "", file.getAbsolutePath()));
        } catch (MalformedURLException ex) {
          log.log(Level.WARNING, "Could not get URL for file: " + file, ex);
        }
      }
    }


    UserClassLoaderFactory factory = new UserClassLoaderFactory();

    return factory.createClassLoader(runtimeClassLoader,
                                     getUrls(classPathBuilder),
                                     root,
                                     allFiles.toArray(new File[allFiles.size()]),
                                     environment);
  }

  private URL[] getUrls(ClassPathBuilder classPathBuilder) {
    URL[] urls = classPathBuilder.getUrls();
    String message = classPathBuilder.getLogMessage();
    if (!message.isEmpty()) {
      // Log to the user's logs.
      ApiProxy.log(new ApiProxy.LogRecord(
          ApiProxy.LogRecord.Level.warn, System.currentTimeMillis() * 1000, message));
    }
    return urls;
  }


  private RuntimeClassLoader getRuntimeClassLoader() {
    ClassLoader classLoader = getClass().getClassLoader();

    if (!(classLoader instanceof RuntimeClassLoader)) {
      throw new RuntimeException(getClass().getName() + " must be loaded by a "
          + RuntimeClassLoader.class.getName() + ". Was loaded by: " + classLoader.getClass()
          .getName());
    }

    return (RuntimeClassLoader) classLoader;
  }

  /**
   * Returns true iff the file both exists and we have the read
   * permission to discover that that is the case.
   *
   * As things currently sit, we won't have the permission to read
   * anything from the user app that didn't exist in the first place.
   */
  private boolean fileExists(File file) {
    try {
      return file.exists();
    } catch (SecurityException e) {
      log.warning("Don't have permissions to test " + file + " for existence.");
      return false;
    }
  }

  private Map<String, Boolean> extractFilesAndDirectories(AppInfo appInfo) {
    Map<String, Boolean> filesAndDirectories = new HashMap<String, Boolean>(appInfo.files().size());
    for (AppInfo.File file : appInfo.files()) {
      String path = file.getPath();
      filesAndDirectories.put(path, false);
      for (int slash = path.indexOf('/'); slash != -1; slash = path.indexOf('/', slash + 1)) {
        filesAndDirectories.put(path.substring(0, slash), true);
      }
    }
    return filesAndDirectories;
  }

}
