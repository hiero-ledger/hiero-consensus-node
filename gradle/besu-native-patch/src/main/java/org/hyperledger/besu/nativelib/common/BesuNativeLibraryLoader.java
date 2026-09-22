/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.hyperledger.besu.nativelib.common;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

public class BesuNativeLibraryLoader {

  /**
   * Wraps JNA with a prescriptive path, removing any platform naming inconsistencies
   *  that might exist.  E.g. linux-gnu-x86-64 vs linux-x86_64.
   *
   *  Note that this method expects the directory space to be "partitioned" only by the arch, and
   *  relies on the different operating systems to have different shared library object
   *  filenames.  E.g. .dylib vs .so vs .dll.
   */
  public static void registerJNA(Class jnaClass, String libraryName) {

    try {
      String moduleContextClassName = Thread.currentThread().getStackTrace()[2].getClassName();
      Class<?> moduleContextClass = Class.forName(moduleContextClassName);

      final Optional<Path> libPath = extract(moduleContextClass, libraryName);

      if (libPath.isPresent()) {
        NativeLibrary lib = NativeLibrary.getInstance(libPath.get().toString());
        Native.register(jnaClass, lib);
      } else {
        // fallback: try loading from library name via JNA
        Native.register(jnaClass, libraryName);
      }
    } catch (UnsatisfiedLinkError __) {
        String exceptionMessage =
            String.format(
                "Couldn't load native library (%s). It wasn't available at %s or the library path.",
                libraryName, asLibraryResourcePath(libraryName));
        throw new RuntimeException(exceptionMessage);
    } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
    }
  }

  public static void loadJNI(Class jniClass, String libraryName) {

    try {
      final Optional<Path> libPath = extract(jniClass, libraryName);

      if (libPath.isPresent()) {
        System.load(libPath.get().toString());
      } else {
        System.loadLibrary(libraryName);
      }
    } catch (UnsatisfiedLinkError __) {
      String exceptionMessage =
          String.format(
              "Couldn't load native library (%s). It wasn't available at %s or the library path.",
              libraryName, asLibraryResourcePath(libraryName));
      throw new RuntimeException(exceptionMessage);
    }
  }


  private static Optional<Path> extract(Class classResource, String libraryName) {
    final String platformNativeLibraryName = System.mapLibraryName(libraryName);

    // load from lib/arch.  replace underscore with dash to avoid platform arch naming oddities
    final String libraryResourcePath = asLibraryResourcePath(libraryName);

    InputStream libraryResource = classResource.getResourceAsStream(libraryResourcePath);

    if (libraryResource == null) {
      // try absolute classpath reference for filesystem resources in case we are running tests:
      libraryResource = classResource.getResourceAsStream("/" + libraryResourcePath);
    }

    if (libraryResource != null) {
      try {
        Path tempDir = Files.createTempDirectory(libraryName + "@");
        tempDir.toFile().deleteOnExit();
        Path tempDll = tempDir.resolve(platformNativeLibraryName);
        tempDll.toFile().deleteOnExit();
        Files.copy(libraryResource, tempDll, StandardCopyOption.REPLACE_EXISTING);
        libraryResource.close();
        return Optional.of(tempDll);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return Optional.empty();
  }

  private static String asLibraryResourcePath(String libraryName) {

    final String platformNativeLibraryName = System.mapLibraryName(libraryName);
    return safeArchLib(platformNativeLibraryName);

  }

  // deal with the variants that might be reported for x86-64
  static String[] X86_VARIANTS = {"amd64", "x86_64", "x64", "ia32e", "EMT64T"};
  private static String safeArchLib(String platformNativeLibraryName) {
    String arch = System.getProperty("os.arch");

    if (Arrays.asList(X86_VARIANTS).contains(arch)) {
      arch = "x86-64";
    }
    // It is important that the folder 'lib-native' contains a '-' such that it is only
    // folder and not a 'java package' to wich visibility rules may be applied by JPMS.
    return String.format("lib-native/%s/%s", arch, platformNativeLibraryName );
  }
}
