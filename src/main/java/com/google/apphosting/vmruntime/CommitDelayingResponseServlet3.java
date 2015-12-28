package com.google.apphosting.vmruntime;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import javax.servlet.http.HttpServletResponse;

/**
 * Additions to {@link CommitDelayingResponse} for handling extra methods introduced in Servlet 3.0.
 *
 */

public class CommitDelayingResponseServlet3 extends CommitDelayingResponse {

  public CommitDelayingResponseServlet3(HttpServletResponse response) throws IOException {
    super(response);
  }

  @Override
  public String getHeader(String name) {
    if (name.equals(CONTENT_LENGTH)) {
      return output.hasContentLength() ? Long.toString(output.getContentLength()) : null;
    }
    return super.getHeader(name);
  }

  @Override
  public Collection<String> getHeaders(String name) {
    if (name.equals(CONTENT_LENGTH) && output.hasContentLength()) {
      return Arrays.asList(new String[] {Long.toString(output.getContentLength())});
    }
    return super.getHeaders(name);
  }

  @Override
  public Collection<String> getHeaderNames() {
    if (output.hasContentLength()) {
      // "Any changes to the returned Collection must not affect this HttpServletResponse."
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(super.getHeaderNames());
      if (output.hasContentLength()) {
        builder.add(CONTENT_LENGTH);
      }
      return builder.build();
    }
    return super.getHeaderNames();
  }
}
