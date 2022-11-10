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

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

abstract class AbstractStorageLocation implements CloseableResource {

  private Path tempDir;
  private Path path;
  private URI uri;

  static <T extends AbstractStorageLocation> T get(
      ExtensionContext extensionContext, Class<T> type, Supplier<T> constructor) {
    return extensionContext
        .getRoot()
        .getStore(Util.NAMESPACE)
        .getOrComputeIfAbsent(type, x -> constructor.get(), type);
  }

  protected AbstractStorageLocation(String discriminator) {
    try {
      String location = System.getProperty("nessie.inttest.location." + discriminator);
      if (location == null) {
        tempDir =
            Files.createTempDirectory(String.format("NesQuEIT-%s-", discriminator))
                .toAbsolutePath();
        path = tempDir;
        uri = tempDir.toUri();
      } else {
        try {
          uri = new URI(location);
        } catch (URISyntaxException e) {
          uri = Paths.get(location).toAbsolutePath().toUri();
        }
        try {
          path = Paths.get(uri).toAbsolutePath();
        } catch (FileSystemNotFoundException | IllegalArgumentException e) {
          // ignore these
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public Path getPath() {
    return path;
  }

  public URI getUri() {
    return uri;
  }

  @Override
  public void close() throws Throwable {
    if (tempDir != null) {
      deleteRecursively(tempDir);
      tempDir = null;
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    Collection<IOException> exceptions = deleteRecursivelyInternal(path);
    if (exceptions != null) {
      IOException e = new IOException("Failed to delete " + path);
      exceptions.forEach(e::addSuppressed);
      throw e;
    }
  }

  private static Collection<IOException> deleteRecursivelyInternal(Path path) {
    Collection<IOException> exceptions = null;
    try {
      if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
          exceptions = deleteDirectoryContentsInsecure(stream);
        }
      }

      // If exceptions is not null, something went wrong trying to delete the contents of the
      // directory, so we shouldn't try to delete the directory as it will probably fail.
      if (exceptions == null) {
        Files.delete(path);
      }

      return exceptions;
    } catch (IOException e) {
      return addException(exceptions, e);
    }
  }

  private static Collection<IOException> deleteDirectoryContentsInsecure(
      DirectoryStream<Path> dir) {
    Collection<IOException> exceptions = null;
    try {
      for (Path entry : dir) {
        exceptions = concat(exceptions, deleteRecursivelyInternal(entry));
      }

      return exceptions;
    } catch (DirectoryIteratorException e) {
      return addException(exceptions, e.getCause());
    }
  }

  private static Collection<IOException> addException(
      Collection<IOException> exceptions, IOException e) {
    if (exceptions == null) {
      exceptions = new ArrayList<>(); // don't need Set semantics
    }
    exceptions.add(e);
    return exceptions;
  }

  private static Collection<IOException> concat(
      Collection<IOException> exceptions, Collection<IOException> other) {
    if (exceptions == null) {
      return other;
    } else if (other != null) {
      exceptions.addAll(other);
    }
    return exceptions;
  }

  public Path forProject(String projectName) {
    return tempDir.resolve(projectName).toAbsolutePath();
  }
}
