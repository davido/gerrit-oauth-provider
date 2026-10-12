// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.oauth.client;

import com.google.common.io.CharStreams;
import com.google.gerrit.common.Nullable;
import com.googlesource.gerrit.plugins.oauth.utils.OAuthUrls;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HttpURLConnection helper for the OAuth providers' outbound token/introspection calls. */
public final class OAuthHttpTransport {
  public static final int DEFAULT_TIMEOUT_MS = 5000;

  private OAuthHttpTransport() {}

  /** An HTTP response: the status code and the (input-or-error) body decoded as UTF-8. */
  public static final class Response {
    public final int code;
    public final String body;

    Response(int code, String body) {
      this.code = code;
      this.body = body;
    }
  }

  /**
   * Performs the request with default timeouts and returns the status code plus the response body
   * (read from the input stream for 2xx, otherwise the error stream). Callers own status handling.
   *
   * <p>The token is only sent over {@code https} or to a loopback host -- refusing to leak a bearer
   * token in cleartext to a misconfigured endpoint.
   */
  public static Response request(
      String method, String url, Map<String, String> headers, @Nullable String body)
      throws IOException {
    requireSecure(url);
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
      connection.setRequestMethod(method);
      connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
      connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
      headers.forEach(connection::setRequestProperty);
      if (body != null) {
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
          os.write(body.getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = connection.getResponseCode();
      String responseBody;
      try (InputStream in =
          (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream()) {
        responseBody =
            (in == null)
                ? ""
                : CharStreams.toString(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
      return new Response(code, responseBody);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /** Rejects sending a token over cleartext to a non-loopback host. Package-private for testing. */
  static void requireSecure(String url) throws IOException {
    if (!OAuthUrls.isSecureOrLoopback(url)) {
      throw new IOException(
          "Refusing to send an OAuth token over insecure http to '"
              + URI.create(url).getHost()
              + "'; the introspection endpoint must use https.");
    }
  }
}
