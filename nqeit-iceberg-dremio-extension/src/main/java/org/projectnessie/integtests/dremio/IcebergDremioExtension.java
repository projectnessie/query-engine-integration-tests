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
package org.projectnessie.integtests.dremio;

import static org.projectnessie.integtests.nessie.internal.Util.checkSupportedParameterType;

import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class IcebergDremioExtension implements ParameterResolver, ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (System.getProperty("dremio.url") == null) {
      return ConditionEvaluationResult.disabled("system property dremio.url is not set");
    }
    return ConditionEvaluationResult.enabled("system property dremio.url is set");
  }

  private static String readRequiredSystemProperty(String s) {
    return Objects.requireNonNull(System.getProperty(s), "required system property not set: " + s);
  }

  private static String dremioUrl() {
    String url = readRequiredSystemProperty("dremio.url");
    if (url.contains("://app.")) {
      // for user convenience we adjust the url for the rest API if necessary:
      // app.dremio.cloud -> api.dremio.cloud
      // see https://docs.dremio.com/cloud/appendix/supported-regions/
      url = url.replaceFirst("://app.", "://api.");
    }
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  @Override
  public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extensionCtx)
      throws ParameterResolutionException {
    if (paramCtx.isAnnotated(Dremio.class)) {
      checkSupportedParameterType(Dremio.class, paramCtx, DremioHelper.class);
      return true;
    }
    return false;
  }

  @Override
  public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extensionCtx)
      throws ParameterResolutionException {
    Optional<Dremio> dremioAnnotation = paramCtx.findAnnotation(Dremio.class);
    if (dremioAnnotation.isPresent()) {
      return buildDremioHelperFromSystemProperties(dremioAnnotation.get().systemPropertyPrefix());
    }
    throw new ParameterResolutionException(
        "Unsupported parameter " + paramCtx.getParameter() + " on " + paramCtx.getTarget());
  }

  private DremioHelper buildDremioHelperFromSystemProperties(String systemPropertyPrefix) {
    if (systemPropertyPrefix == null || !systemPropertyPrefix.endsWith(".")) {
      throw new IllegalArgumentException("Invalid systemPropertyPrefix: " + systemPropertyPrefix);
    }
    String apiBaseUrl = dremioUrl(); // same for all DremioHelper test params
    String token = readRequiredSystemProperty(systemPropertyPrefix + "token");
    String projectId = readRequiredSystemProperty(systemPropertyPrefix + "project-id");
    String catalogName = readRequiredSystemProperty(systemPropertyPrefix + "catalog-name");

    return new DremioHelper(token, apiBaseUrl, projectId, catalogName);
  }
}
