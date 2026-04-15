// SPDX-License-Identifier: Apache-2.0
package org.hiero.gradle.transforms;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;

@CacheableTransform
public abstract class BesuNativePatchTransform implements TransformAction<TransformParameters.None> {

    // Modified loader, see: https://github.com/hyperledger/besu-native/pull/290
    public static final String BESU_NATIVE_LIBRARY_LOADER_CLASS =
            "org/hyperledger/besu/nativelib/common/BesuNativeLibraryLoader.class";

    // See modules in: https://github.com/hyperledger/besu-native
    public static final List<String> BESU_NATIVE_JARS = Arrays.asList(
            "besu-native-common",
            "arithmetic",
            "blake2bf",
            "boringssl",
            "constantine",
            "gnark",
            "ipa-multipoint",
            "secp256k1",
            "secp256r1");

    @InputArtifact
    @Classpath
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        File originalJar = getInputArtifact().get().getAsFile();
        boolean isBesuNativeJar = BESU_NATIVE_JARS.stream()
                .anyMatch(name -> originalJar.getName().startsWith(name + "-"));
        if (isBesuNativeJar) {
            String baseName = originalJar.getName().replaceFirst("[.][^.]+$", "");
            File patchedJar = outputs.file(baseName + "-besu.jar");
            patch(originalJar, patchedJar);
        } else {
            outputs.file(originalJar);
        }
    }

    public void patch(File besuJar, File patchedJar) {
        try {
            patchedJar.createNewFile();
            try (JarOutputStream outJar = new JarOutputStream(java.nio.file.Files.newOutputStream(patchedJar.toPath()));
                 JarInputStream inJar = new JarInputStream(java.nio.file.Files.newInputStream(besuJar.toPath()))) {

                java.util.jar.Manifest inManifest = inJar.getManifest();
                if (inManifest != null) {
                    JarEntry outManifest = newReproducibleEntry(JarFile.MANIFEST_NAME);
                    outJar.putNextEntry(outManifest);
                    inManifest.write(new BufferedOutputStream(outJar));
                    outJar.closeEntry();
                }

                JarEntry jarEntry = inJar.getNextJarEntry();
                while (jarEntry != null) {
                    String name = jarEntry.getName();
                    byte[] content = inJar.readAllBytes();

                    if (name.startsWith("lib/")) {
                        jarEntry = newReproducibleEntry(name.replaceFirst("lib/", "lib-native/"));
                    }
                    if (name.equals(BESU_NATIVE_LIBRARY_LOADER_CLASS)) {
                        try (var stream = BesuNativePatchTransform.class
                                .getResourceAsStream("/" + BESU_NATIVE_LIBRARY_LOADER_CLASS)) {
                            if (stream == null) throw new IllegalStateException(
                                    "Resource not found: " + BESU_NATIVE_LIBRARY_LOADER_CLASS);
                            content = stream.readAllBytes();
                        }
                    }

                    jarEntry.setCompressedSize(-1);
                    outJar.putNextEntry(jarEntry);
                    outJar.write(content);
                    outJar.closeEntry();

                    jarEntry = inJar.getNextJarEntry();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to patch JAR: " + besuJar.getName(), e);
        }
    }

    private JarEntry newReproducibleEntry(String name) {
        JarEntry entry = new JarEntry(name);
        entry.setTime(new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis());
        return entry;
    }
}
