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

package com.googlesource.gerrit.plugins.oauth.base;

import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import java.io.IOException;

/**
 * Validates a bearer {@code access_token} from the Git-over-HTTP path and maps it to Gerrit user
 * info. Each provider supplies one implementation (local JWKS or remote introspection).
 */
public interface OAuthTokenValidator {
  /** Validates the bearer token and returns the mapped user, or throws on any failure. */
  OAuthUserInfo validate(String bearerToken) throws IOException;
}
