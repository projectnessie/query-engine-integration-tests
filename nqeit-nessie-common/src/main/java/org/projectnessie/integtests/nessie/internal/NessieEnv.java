/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.integtests.nessie.internal;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.projectnessie.client.NessieClientBuilder;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.Branch;

public class NessieEnv implements CloseableResource {

  private static final int NESSIE_PORT = Integer.getInteger("quarkus.http.test-port", 19121);
  private static final String NESSIE_URI =
      System.getProperty(
          "quarkus.http.url", String.format("http://localhost:%d/api/v2", NESSIE_PORT));

  private NessieApiV1 nessieApiV1;
  private NessieApiV2 nessieApiV2;
  private final Branch initialDefaultBranch;
  private final long startedNanos;
  private final String startedDateTimeString;

  public static NessieEnv get(ExtensionContext extensionContext) {
    return extensionContext
        .getRoot()
        .getStore(Util.NAMESPACE)
        .getOrComputeIfAbsent(NessieEnv.class, x -> new NessieEnv(), NessieEnv.class);
  }

  private NessieEnv() {
    NessieClientBuilder clientBuilder;
    try {
      clientBuilder =
          (NessieClientBuilder)
              NessieClientBuilder.class
                  .getDeclaredMethod("createClientBuilder", String.class, String.class)
                  .invoke(null, null, null);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException x) {
      try {
        clientBuilder =
            (NessieClientBuilder)
                Class.forName("org.projectnessie.client.http.HttpClientBuilder")
                    .getDeclaredMethod("builder")
                    .invoke(null);
      } catch (ClassNotFoundException
          | NoSuchMethodException
          | IllegalAccessException
          | InvocationTargetException e) {
        e.addSuppressed(x);
        throw new RuntimeException(e);
      }
    }
    // Retain this (theoretically unnecessary) cast! Otherwise `./gradlew intTest
    if (NESSIE_URI.contains("api/v1")) {
      nessieApiV1 = clientBuilder.withUri(NESSIE_URI).build(NessieApiV1.class);
    } else {
      nessieApiV2 = clientBuilder.withUri(NESSIE_URI).build(NessieApiV2.class);
    }
    try {
      initialDefaultBranch = getApi().getDefaultBranch();
    } catch (NessieNotFoundException e) {
      getApi().close();
      throw new RuntimeException(e);
    }

    startedNanos = System.nanoTime() % 1_000_000;
    startedDateTimeString = Util.dateTimeString();
  }

  public String getNessieUri() {
    return NESSIE_URI;
  }

  public Branch getInitialDefaultBranch() {
    return initialDefaultBranch;
  }

  public long getStartedNanos() {
    return startedNanos;
  }

  public NessieApiV1 getApi() {
    if (nessieApiV1 == null) {
      return getApiV2();
    }
    return nessieApiV1;
  }

  public NessieApiV2 getApiV2() {
    if (nessieApiV2 == null) {
      throw new IllegalStateException("requesting V2 api aginst a V1 endpoint");
    }
    return nessieApiV2;
  }

  @Override
  public void close() {
    NessieApiV1 apiV1 = nessieApiV1;
    nessieApiV1 = null;
    if (apiV1 != null) {
      apiV1.close();
    }
    NessieApiV2 apiV2 = nessieApiV2;
    nessieApiV2 = null;
    if (apiV2 != null) {
      apiV2.close();
    }
  }

  public String getStartedDateTimeString() {
    return startedDateTimeString;
  }
}
