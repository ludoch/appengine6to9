
package com.google.apphosting.runtime;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ClassPathUtils {
  private static final Logger logger =
      Logger.getLogger(ClassPathUtils.class.getName());

  private static final String RUNTIME_IMPL_PROPERTY = "classpath.runtime-impl";
  private static final String RUNTIME_SHARED_PROPERTY = "classpath.runtime-shared";
  private static final String USER_PRIVILEGED_PROPERTY = "classpath.user-privileged";
  private static final String PREBUNDLED_PROPERTY = "classpath.prebundled";
  private static final String API_PROPERTY = "classpath.api-map";
  private static final String CONNECTOR_J_PROPERTY = "classpath.connector-j";

  private final File root;
  private final Map<String, File> apiVersionMap;
  private Map<File, File> runtimeProvidedPrecompiledFileMap;
  private Collection<File> runtimeProvidedFiles;

  public ClassPathUtils() {
    this(null);
  }

  public ClassPathUtils(File root) {
    this.root = root;
    apiVersionMap = new HashMap<String, File>();
    initRuntimeProvidedFiles();
  }

  public URL[] getRuntimeImplUrls() {
    return parseClasspath(System.getProperty(RUNTIME_IMPL_PROPERTY));
  }

  public URL[] getRuntimeSharedUrls() {
    //   if (specLevel == ServletSpecification.V3_0) {
    String runtime = System.getProperty(RUNTIME_SHARED_PROPERTY);
    String absolutePath = "";
    if (runtime.startsWith("/")) {
      absolutePath = new File(runtime).getParentFile().getAbsolutePath() + "/";
    }
    return parseClasspath(System.getProperty(RUNTIME_SHARED_PROPERTY)
        + ":" + absolutePath + "servlet_api31_deploy.jar");
    //   } else {
    //     return parseClasspath(System.getProperty(RUNTIME_SHARED_PROPERTY)
    //         + ":servlet_api_deploy.jar");
    //   }
  }
  public URL[] getUserPrivilegedUrls() {
    return parseClasspath(System.getProperty(USER_PRIVILEGED_PROPERTY));
  }

  public URL[] getPrebundledUrls() {
    return parseClasspath(System.getProperty(PREBUNDLED_PROPERTY));
  }
  
  public URL[] getConnectorJUrls() {
    return parseClasspath(System.getProperty(CONNECTOR_J_PROPERTY));
  }

  /**
   * Returns a {@link File} for the API jar that corresponds to the
   * specified version, or @{code null} if no jar for this version is
   * available.
   */
  public File getApiJarForVersion(String apiVersion) {
    return apiVersionMap.get(apiVersion);
  }

  /**
   * Returns all runtime-provided files which are loaded in the UserClassLoader
   * as unprivileged user code. This includes code like the appengine API as
   * well as bits of the JRE that we implement at the user-level.
   */
  public Collection<File> getRuntimeProvidedFiles() {
    return Collections.unmodifiableCollection(runtimeProvidedFiles);
  }

  /**
   * Returns a map of runtime-provided files to their precompiled versions.
   * Some runtime-provided may not have precompiled versions, and thus, may
   * not have entries in this map.
   */
  public Map<File, File> getRuntimeProvidedPrecompiledFileMap() {
    return Collections.unmodifiableMap(runtimeProvidedPrecompiledFileMap);
  }

  /**
   * Parse the specified string into individual files (using the
   * machine's path separator) and return an array containing a
   * {@link URL} object representing each file.
   */
  public URL[] parseClasspath(String classpath) {
    List<URL> urls = new ArrayList<URL>();

    StringTokenizer tokenizer = new StringTokenizer(classpath, File.pathSeparator);
    while (tokenizer.hasMoreTokens()) {
      String token = tokenizer.nextToken();
      try {
        // Avoid File.toURI() and File.toURL() here as they do an
        // unnecessary stat call.
        File f = new File(root, token);
        if (!f.exists() && f.getAbsolutePath().contains("servlet")) {
          throw new RuntimeException("File not there: " + f.getAbsolutePath());
        }
        urls.add(new URL("file", "", f.getAbsolutePath()));
      } catch (MalformedURLException ex) {
        logger.log(Level.WARNING, "Could not parse " + token + " as a URL, ignoring.", ex);
      }
    }

    return urls.toArray(new URL[0]);
  }

  private void initRuntimeProvidedFiles() {
    runtimeProvidedFiles = new ArrayList<File>();
    runtimeProvidedPrecompiledFileMap = new HashMap<File,File>();
    addJars(runtimeProvidedFiles, runtimeProvidedPrecompiledFileMap, getPrebundledUrls());
    addJars(runtimeProvidedFiles, runtimeProvidedPrecompiledFileMap, getConnectorJUrls());
    // We consider API jars to also be prebundled.
    addApiJars(runtimeProvidedFiles, runtimeProvidedPrecompiledFileMap);
  }

  /**
   * Returns the corresponding preverified file for a not-preverified,
   * runtime-provided file.
   */
  private File getPrecompiledFile(File unverifiedFile) {
    return new File(unverifiedFile.getParent(),
        unverifiedFile.getName() + ".preverified");
  }

  private void addJars(Collection<File> files, Map<File, File> fileMap, URL[] urls) {
    for (URL url : urls) {
      File f = new File(url.getPath());
      File precompiledFile = getPrecompiledFile(f);
      files.add(f);
      if (precompiledFile.exists()) {
        fileMap.put(f, getPrecompiledFile(f));
      } else {
        logger.warning("Could not find the precompiled file, " + precompiledFile.getAbsolutePath()
            + " for jar " + f.getAbsolutePath());
      }
    }
  }

  private void addApiJars(Collection<File> files, Map<File,File> fileMap) {
    // The string for the api mapping follows the grammar:
    // <single-mapping>     is <version>=<path>
    // <additional-mapping> is :<version>=<path>
    // <mappings>           is <single-mapping> <additional-mapping>+
    String apiMapping = System.getProperty(API_PROPERTY);

    if (apiMapping != null && !apiMapping.isEmpty()) {
      StringTokenizer tokenizer = new StringTokenizer(apiMapping, File.pathSeparator);
      while (tokenizer.hasMoreTokens()) {
        String token = tokenizer.nextToken();
        int equals = token.indexOf('=');
        if (equals != -1) {
          String apiVersion = token.substring(0, equals);
          String filename = token.substring(equals + 1);
          File file = new File(root, filename);
          apiVersionMap.put(apiVersion, file);
          files.add(file);

          File precompiledFile = getPrecompiledFile(file);
          if (precompiledFile.exists()) {
            fileMap.put(file, precompiledFile);
          }
        } else {
          logger.warning("Could not parse " + token + " as api-version=jar, ignoring.");
        }
      }
    } else {
      logger.severe("Property " + API_PROPERTY + " not set, no API versions available.");
    }
  }
}
