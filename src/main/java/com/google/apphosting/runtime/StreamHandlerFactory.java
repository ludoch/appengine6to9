
package com.google.apphosting.runtime;


import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link URLStreamHandlerFactory} which installs
 * {@link URLStreamHandler URLStreamHandlers} that the
 * App Engine runtime needs to support. (For example,
 * for the "random" and "http" protocols).
 *
 */
class StreamHandlerFactory implements URLStreamHandlerFactory {

  /**
   * The single instance of this factory.
   */
  private static StreamHandlerFactory factory;

  /**
   * A URL that we can test reads for random bytes from.
   */
  private static final String randomUrl = "random://foo";

  /**
   * Sun's class which handles seed generation from a URL.
   */
  private static final String URL_SEED_GENERATOR
      = "sun.security.provider.SeedGenerator$ThreadedSeedGenerator";

  /**
   * Sun's class which handles seed generation.
   */
  private static final String SEED_GENERATOR = "sun.security.provider.SeedGenerator";

  /**
   * The name of the field which is the only instance of the SeedGenerator.
   */
  private static final String SEED_GENERATOR_INSTANCE = "instance";

  /**
   * The field of the url name that is stored in URLSeedGenerator.
   */
  private static final String URL_SEED_GENERATOR_DEVICE_NAME = "deviceName";

  private Map<String, URLStreamHandler> handlers = new HashMap<String, URLStreamHandler>();



 
  public URLStreamHandler createURLStreamHandler(String protocol) {
    return handlers.get(protocol);
  }

  private void testRandom() {
    // Test and make sure the random stream handler got installed correctly.
    // (It's difficult to test this from a normal TestCase)

    try {
      InputStream input = new URL(randomUrl).openStream();
      //noinspection ResultOfMethodCallIgnored
      input.read(); // we're only testing the read, fine to ignore the return value.
      input.close();
    } catch (IOException e) {
      throw new RuntimeException("Unable to read from " + randomUrl, e);
    }

    try {
      Class seedClass = Class.forName(SEED_GENERATOR);
      Field f = seedClass.getDeclaredField(SEED_GENERATOR_INSTANCE);
      f.setAccessible(true);
      Object generator = f.get(null);
      Class generatorClass = generator.getClass();
      if (!generatorClass.getName().equals(URL_SEED_GENERATOR)) {
        throw new RuntimeException("Wrong SeedGenerator class: " + generatorClass);
      }

      Field deviceNameField = generatorClass.getDeclaredField(URL_SEED_GENERATOR_DEVICE_NAME);
      deviceNameField.setAccessible(true);
      String deviceName = (String) deviceNameField.get(generator);
      if (!deviceName.equals(randomUrl)) {
        throw new RuntimeException("Failed to install " + randomUrl + " as random device. " +
            "Current random device = " + deviceName);
      }
    } catch (ClassNotFoundException e) {
      throw unexpectedFailure(e);
    } catch (NoSuchFieldException e) {
      throw unexpectedFailure(e);
    } catch (IllegalAccessException e) {
      throw unexpectedFailure(e);
    }
  }


  private static RuntimeException unexpectedFailure(Exception e) {
    throw new RuntimeException("Unexpected failure while trying to verify handler installation.",
        e);
  }
}
