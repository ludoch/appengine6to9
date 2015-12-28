// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.apphosting.utils.config;

import org.eclipse.jetty.xml.XmlParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * @author ludo@google.com (ludovic Champenois)
 */
public class ServletVersionChecker {

  public static int getServletVersion(File appRoot) {

    if (appRoot.isFile()) {
      // war file TODO(ludo), for now 2.5 spec...only for dev app server
      return 2;
    }
    File webxml = new File(appRoot, "WEB-INF/web.xml");
    try {
      if (!webxml.exists()) {
        return 3; //new for Servlet 3.0: may not exists.
      }
      InputStream is = new FileInputStream(webxml);
      XmlParser xmlParser = new XmlParser();
      XmlParser.Node root = xmlParser.parse(is);
      String version = root.getAttribute("version", "DTD");
      if ("DTD".equals(version)) { //very old spec
        return 2;
      } else {
        int dot = version.indexOf(".");
        if (dot > 0) {
          return Integer.parseInt(version.substring(0, dot));
        }
      }
    } catch (Exception e) {
      // nothing much to do but default
    }
    return 2;
  }
}
