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
package org.projectnessie.integtests.nessie;

import static org.projectnessie.integtests.nessie.internal.Util.checkSupportedParameterType;
import static org.projectnessie.integtests.nessie.internal.Util.nessieClientParams;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.projectnessie.client.api.NessieApi;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.integtests.nessie.internal.DefaultBranchPerRun;
import org.projectnessie.integtests.nessie.internal.NessieEnv;
import org.projectnessie.integtests.nessie.internal.ReferencesHelper;
import org.projectnessie.model.Branch;
import org.projectnessie.model.Reference;

public class NessieTestsExtension implements ParameterResolver, BeforeEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    DefaultBranchPerRun.get(context);
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(NessieRefName.class)) {
      checkSupportedParameterType(NessieRefName.class, parameterContext, CharSequence.class);
      return true;
    }
    if (parameterContext.isAnnotated(NessieAPI.class)) {
      checkSupportedParameterType(NessieAPI.class, parameterContext, NessieApi.class);
      return true;
    }
    if (parameterContext.isAnnotated(NessieDefaultBranch.class)) {
      checkSupportedParameterType(
          NessieAPI.class, parameterContext, CharSequence.class, Reference.class);
      return true;
    }
    if (parameterContext.isAnnotated(NessieClientParams.class)) {
      checkSupportedParameterType(NessieClientParams.class, parameterContext, Map.class);
      return true;
    }

    return false;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {

    Optional<NessieRefName> refName = parameterContext.findAnnotation(NessieRefName.class);
    if (refName.isPresent()) {
      String name = refName.get().name();
      if (name.isEmpty()) {
        name = parameterContext.getParameter().getName();
      }
      return ReferencesHelper.get(extensionContext).generateRefNameFor(name, extensionContext);
    }
    if (parameterContext.isAnnotated(NessieAPI.class)) {
      if (NessieApiV2.class.isAssignableFrom(parameterContext.getParameter().getType())) {
        return NessieEnv.get(extensionContext).getApiV2();
      }
      return NessieEnv.get(extensionContext).getApi();
    }
    if (parameterContext.isAnnotated(NessieDefaultBranch.class)) {
      DefaultBranchPerRun defaultBranch = DefaultBranchPerRun.get(extensionContext);
      Branch branch = defaultBranch.getDefaultBranch();
      if (Reference.class.isAssignableFrom(parameterContext.getParameter().getType())) {
        return branch;
      }
      if (CharSequence.class.isAssignableFrom(parameterContext.getParameter().getType())) {
        return branch.getName();
      }
    }
    if (parameterContext.isAnnotated(NessieClientParams.class)) {
      return nessieClientParams(extensionContext, null);
    }

    return null;
  }
}
