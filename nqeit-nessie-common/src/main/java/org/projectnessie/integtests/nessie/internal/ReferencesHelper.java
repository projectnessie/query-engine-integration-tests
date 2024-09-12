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

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.model.Branch;
import org.projectnessie.model.Reference;
import org.projectnessie.model.Tag;

public class ReferencesHelper implements CloseableResource {

  private final Set<String> generatedReferenceNames = new HashSet<>();
  private final NessieEnv env;

  public static ReferencesHelper get(ExtensionContext extensionContext) {
    return extensionContext
        .getStore(Util.NAMESPACE)
        .getOrComputeIfAbsent(
            ReferencesHelper.class,
            x -> new ReferencesHelper(extensionContext),
            ReferencesHelper.class);
  }

  private ReferencesHelper(ExtensionContext extensionContext) {
    this.env = NessieEnv.get(extensionContext);
  }

  @Override
  public void close() throws Throwable {
    if (Boolean.getBoolean("nessie.test.keepReferences")) {
      return;
    }
    NessieApiV2 api = env.getApi();
    for (Reference ref : api.getAllReferences().get().getReferences()) {
      if (ref instanceof Branch && generatedReferenceNames.remove(ref.getName())) {
        api.deleteBranch().branchName(ref.getName()).hash(ref.getHash()).delete();
      }
      if (ref instanceof Tag && generatedReferenceNames.remove(ref.getName())) {
        api.deleteTag().tagName(ref.getName()).hash(ref.getHash()).delete();
      }
    }
  }

  public String generateRefNameFor(String name, ExtensionContext extensionContext) {
    StringBuilder sb = new StringBuilder();
    sb.append(name);
    extensionContext.getTestClass().ifPresent(c -> sb.append('_').append(c.getSimpleName()));
    sb.append('_').append(env.getStartedNanos());
    extensionContext.getTestMethod().ifPresent(m -> sb.append('_').append(m.getName()));
    sb.append('_').append(generatedReferenceNames.size());

    String refName = sb.toString();
    generatedReferenceNames.add(refName);
    return refName;
  }
}
