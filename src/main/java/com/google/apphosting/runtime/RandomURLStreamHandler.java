
package com.google.apphosting.runtime;

import com.google.common.primitives.UnsignedBytes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;


class RandomURLStreamHandler extends URLStreamHandler {
  

  
  private URLConnection randomUrlConnection = new RandomURLConnection();
  
  @Override
  protected URLConnection openConnection(URL u) throws IOException {
    return randomUrlConnection;
  }
  
  private class RandomURLConnection extends URLConnection {
    public RandomURLConnection() {
      super(null);
    } 
    
    @Override
    public void connect() throws IOException {
      connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {      
      return new InputStream() {

        @Override
        public int read(byte b[]) throws IOException {
          return read(b, 0, b.length);
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {         
          byte[] bytes = new byte[len];        
          System.arraycopy(bytes, 0, b, off, len);
          return bytes.length;
        }
        
        @Override
        public int read() throws IOException {
          byte[] bytes = new byte[1];
          return UnsignedBytes.toInt(bytes[0]);
        }

        @Override
        public int available() throws IOException {
          return Integer.MAX_VALUE;
        }

        @Override
        public long skip(long n) {
          return n;
        }
      };
    }
  }
}
