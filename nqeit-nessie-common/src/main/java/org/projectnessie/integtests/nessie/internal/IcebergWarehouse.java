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

import org.junit.jupiter.api.extension.ExtensionContext;

public class IcebergWarehouse extends AbstractStorageLocation {

  public static final String DISCRIMINATOR = "iceberg.warehouse";

  public static IcebergWarehouse get(ExtensionContext extensionContext) {
    return AbstractStorageLocation.get(
        extensionContext, IcebergWarehouse.class, IcebergWarehouse::new);
  }

  private IcebergWarehouse() {
    super(DISCRIMINATOR);
  }
}
