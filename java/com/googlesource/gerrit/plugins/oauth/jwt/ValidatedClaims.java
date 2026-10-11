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

package com.googlesource.gerrit.plugins.oauth.jwt;

import com.google.gson.JsonObject;
import java.util.Set;

/** Verified JWT claims returned by {@link OidcJwtValidator#validate(String)}. */
public final class ValidatedClaims {
  private final String subject;
  private final Set<String> audience;
  private final String issuer;
  private final JsonObject payload;

  ValidatedClaims(String subject, Set<String> audience, String issuer, JsonObject payload) {
    this.subject = subject;
    this.audience = audience;
    this.issuer = issuer;
    this.payload = payload;
  }

  public String subject() {
    return subject;
  }

  public Set<String> audience() {
    return audience;
  }

  public String issuer() {
    return issuer;
  }

  /** Raw claim payload as a JSON object, for provider-specific claim extraction. */
  public JsonObject payload() {
    return payload;
  }
}
