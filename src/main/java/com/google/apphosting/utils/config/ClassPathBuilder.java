package com.google.apphosting.utils.config;

import com.google.apphosting.utils.config.AppEngineWebXml.ClassLoaderConfig;
import com.google.apphosting.utils.config.AppEngineWebXml.PrioritySpecifierEntry;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies class loader configuration rules to class path {@link URL URLs}.
 */
public class ClassPathBuilder {
  // classes directory URLs end in "/classes" and optionally a terminating "/"
  private static final Pattern CLASSES_REGEX = Pattern.compile(".*/classes/?");

  // Matches "appengine-api.jar" and paths like appengine-api-1.0-sdk-1.7.0.jar
  private static final Pattern APPENGINE_API_REGEX =
      Pattern.compile(".*/appengine-api(-?[0-9\\.]*-sdk-[0-9\\.]*)?\\.jar");

  // Pairs a URL and priority for sorting.
  private static class UrlPriority {
    final URL url;
    final double priority;

    UrlPriority(URL url, double priority) {
      this.url = url;
      this.priority = priority;
    }

    // NOTE: Equality and hash code is *only* sensitive to url because
    // we don't want the urls set to have duplicates of the url.
    // Generated by eclipse.
    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((url == null) ? 0 : url.hashCode());
      return result;
    }

    // Generated by eclipse.
    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      UrlPriority other = (UrlPriority) obj;
      if (url == null) {
        if (other.url != null) return false;
      } else if (!url.equals(other.url)) return false;
      return true;
    }
  }

  // Comparator for sorting UrlPriority objects.
  private static final Comparator<UrlPriority> URL_PRIORITY_COMP = new Comparator<UrlPriority>() {
      @Override
      public int compare(UrlPriority o1, UrlPriority o2) {
        double diff = o1.priority - o2.priority;
        if (diff < 0) {
          return 1;
        }
        if (diff > 0) {
          return -1;
        }
        return 0;
      }
    };

  // The set of added URL/priority pairs.
  private Set<UrlPriority> urls = new LinkedHashSet<UrlPriority>();

  // The priority specifiers.
  private final List<PrioritySpecifierEntry> priorityEntries;

  // For detecting unused priority specifiers.
  private final boolean[] usedPrioritySpecifiers;

  private URL[] sortedUrls = null;

  /**
   * @param classLoaderConfig The class loader config, may be null.
   */
  public ClassPathBuilder(ClassLoaderConfig classLoaderConfig) {
    if (classLoaderConfig == null) {
      priorityEntries = ImmutableList.of();
    } else {
      priorityEntries = classLoaderConfig.getEntries();
    }
    usedPrioritySpecifiers = new boolean[priorityEntries.size()];
  }

  // Add a URL based on priority.
  private void addUrl(URL url, double defaultPriority) {
    if (sortedUrls != null) {
      throw new IllegalStateException("add* calls are not allowed after getUrls() has been called");
    }
    Double priority = findPriority(url);
    urls.add(new UrlPriority(url, null == priority ? defaultPriority : priority));
  }

  // Return the designated priority for a given URL based on the priorityEntries.
  private Double findPriority(URL url) {
    // Get the filename part of the url.
    String fileName = new File(url.getPath()).getName();

    for (int i = 0; i < usedPrioritySpecifiers.length; ++i) {
      if (priorityEntries.get(i).getFilename().equals(fileName)) {
        usedPrioritySpecifiers[i] = true;
        return priorityEntries.get(i).getPriorityValue();
      }
    }
    return null;
  }

  /**
   * Add the classes {@link URL URLs}.
   */
  public void addClassesUrl(URL url) {
    addUrl(url, 100.0d);
  }

  /**
   * Add the appengine-api.jar {@link URL URLs}.
   */
  public void addAppengineJar(URL url) {
    addUrl(url, 0.5d);
  }

  /**
   * Add application specific jar {@link URL URLs}.
   */
  public void addAppJar(URL url) {
    addUrl(url, 0.0d);
  }

  /**
   * Returns the class loader urls in the order modified by the priority
   * specifiers in the {@link ClassLoaderConfig} passed to the constructor.
   */
  public URL[] getUrls() {
    if (sortedUrls == null) {
      UrlPriority[] classPath = urls.toArray(new UrlPriority[urls.size()]);
      // This is a stable sort.  It should not change the order in which the
      // URLs were added.
      Arrays.sort(classPath, URL_PRIORITY_COMP);

      sortedUrls = new URL[classPath.length];
      for (int i = 0; i < classPath.length; ++i) {
        sortedUrls[i] = classPath[i].url;
      }
    }
    return sortedUrls;
  }

  /**
   * Returns a log message if there were unused specifiers or an empty string.
   * <p>Must be called after calling {@link #getUrls()}.
   */
  public String getLogMessage() {
    if (sortedUrls == null) {
      throw new IllegalStateException(
          "Cannot call getLogMessage() without first calling getUrls()");
    }
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < usedPrioritySpecifiers.length; ++i) {
      if (!usedPrioritySpecifiers[i]) {
        builder.append("priority-specifier: filename: ");
        builder.append(priorityEntries.get(i).getFilename());
        if (priorityEntries.get(i).getPriority() != null) {
          builder.append(" priority: ");
          builder.append(priorityEntries.get(i).getPriorityValue());
        }
      }
    }
    String errors = builder.toString();
    if (!errors.isEmpty()) {
      return
          "appengine-web.xml contains unused class-loader-config priority-specifier values.\n" +
          "unused values: " + errors + "\nresulting classpath: " + Arrays.toString(sortedUrls) +
          "\nTo remove this warning, remove the unused priority-specifier values " +
          "from appengine-web.xml or add a file matching the unused priority-specifier values.";
    }
    return "";
  }

  /**
   * Scans through a collection of URLs for various patterns and adds them
   * with the correct priority.
   */
  public void addUrls(Collection<URL> urls) {
    for (URL url : urls) {
      if (CLASSES_REGEX.matcher(url.getPath()).matches()) {
        addClassesUrl(url);
      } else if (APPENGINE_API_REGEX.matcher(url.getPath()).matches()) {
        addAppengineJar(url);
      } else {
        addAppJar(url);
      }
    }
  }
}
