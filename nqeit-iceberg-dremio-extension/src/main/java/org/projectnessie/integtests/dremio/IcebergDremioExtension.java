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

import java.util.Objects;
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
    return readRequiredSystemProperty("dremio.url");
  }

  private static String dremioToken() {
    return readRequiredSystemProperty("dremio.token");
  }

  private static String dremioProjectId() {
    return readRequiredSystemProperty("dremio.project-id");
  }

  private static String dremioCatalogName() {
    return readRequiredSystemProperty("dremio.catalog-name");
  }

  @Override
  public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extensionCtx)
      throws ParameterResolutionException {
    return paramCtx.getParameter().getType().equals(DremioHelper.class);
  }

  @Override
  public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extensionCtx)
      throws ParameterResolutionException {
    if (paramCtx.getParameter().getType().equals(DremioHelper.class)) {
      return new DremioHelper(dremioToken(), dremioUrl(), dremioProjectId(), dremioCatalogName());
    }
    throw new ParameterResolutionException(
        "Unsupported parameter " + paramCtx.getParameter() + " on " + paramCtx.getTarget());
  }
}
