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

/** How the client parses the token endpoint's response body. */
public enum TokenResponseFormat {
  /**
   * {@code application/json} body: {@code {"access_token":...,"token_type":...}}. The common case,
   * and CAS when {@code use-json-extractor = true}.
   */
  JSON,
  /**
   * {@code application/x-www-form-urlencoded} body: {@code access_token=...&token_type=...}. Used
   * by GitHub's OAuth-App token endpoint and by CAS when {@code use-json-extractor = false} (its
   * default).
   */
  FORM_URL_ENCODED
}
