// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.oauth;

import static com.google.common.truth.Truth.assertThat;

import com.github.scribejava.core.extractors.OAuth2AccessTokenJsonExtractor;
import org.junit.Before;
import org.junit.Test;

public class Facebook2ApiTest {
  private Facebook2Api api;

  @Before
  public void setUp() {
    api = new Facebook2Api();
  }

  @Test
  public void testAccessTokenExtractor() {
    assertThat(api.getAccessTokenExtractor()).isInstanceOf(OAuth2AccessTokenJsonExtractor.class);
  }
}
