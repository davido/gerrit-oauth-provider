// Copyright (C) 2025 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth.utils;

import com.google.common.base.Preconditions;
import com.google.gerrit.common.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class JsonUtil {

  public static boolean isNull(JsonElement e) {
    return e == null || e.isJsonNull();
  }

  @Nullable
  public static String asString(JsonElement e) {
    return isNull(e) ? null : e.getAsString();
  }

  /** Returns the first of {@code keys} present as a JSON primitive, or {@code null}. */
  @Nullable
  public static JsonElement firstPresent(JsonObject o, String... keys) {
    for (String key : keys) {
      JsonElement e = o.get(key);
      if (e != null && e.isJsonPrimitive()) {
        return e;
      }
    }
    return null;
  }

  /**
   * Returns the string value of the first of {@code keys} present as a primitive, or {@code null}.
   */
  @Nullable
  public static String firstString(JsonObject o, String... keys) {
    return asString(firstPresent(o, keys));
  }

  /** Returns the decoded JSON payload (2nd segment) of a JWT (base64url encoded). */
  public static String jwtPayloadJson(String jwt) throws IOException {
    try {
      String[] parts = jwt.split("\\.", -1);
      Preconditions.checkState(
          parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty());
      return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid JWT payload encoding", e);
    }
  }
}
