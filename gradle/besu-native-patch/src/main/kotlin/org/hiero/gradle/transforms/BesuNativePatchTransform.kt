// SPDX-License-Identifier: Apache-2.0
package org.hiero.gradle.transforms

import java.io.BufferedOutputStream
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath

@CacheableTransform
abstract class BesuNativePatchTransform : TransformAction<TransformParameters.None> {
    companion object {
        // Modified loader, see: https://github.com/hyperledger/besu-native/pull/290
        const val BESU_NATIVE_LIBRARY_LOADER_CLASS =
            "org/hyperledger/besu/nativelib/common/BesuNativeLibraryLoader.class"

        // Log4j2 plugin cache file - should be excluded from besu-util to avoid duplicates
        // with log4j-core when creating shadow JARs
        const val LOG4J_PLUGINS_DAT =
            "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"

        // See modules in: https://github.com/hyperledger/besu-native
        val BESU_NATIVE_JARS =
            listOf(
                "besu-native-common",
                "arithmetic",
                "blake2bf",
                "boringssl",
                "constantine",
                "gnark",
                "ipa-multipoint",
                "secp256k1",
                "secp256r1",
            )

        // JARs that need Log4j2Plugins.dat filtered out to avoid shadow JAR conflicts
        val BESU_JARS_WITH_LOG4J_FILTER = listOf("besu-util")
    }

    @get:InputArtifact
    @get:Classpath
    protected abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val originalJar = inputArtifact.get().asFile
        if (BESU_NATIVE_JARS.any { originalJar.name.startsWith("$it-") }) {
            val patchedJar = outputs.file("${originalJar.nameWithoutExtension}-besu.jar")
            patch(originalJar, patchedJar, filterLog4jPlugins = false)
        } else if (BESU_JARS_WITH_LOG4J_FILTER.any { originalJar.name.startsWith("$it-") }) {
            val patchedJar = outputs.file("${originalJar.nameWithoutExtension}-filtered.jar")
            patch(originalJar, patchedJar, filterLog4jPlugins = true)
        } else {
            outputs.file(originalJar)
        }
    }

    fun patch(besuJar: File, patchedJar: File, filterLog4jPlugins: Boolean = false) {
        patchedJar.createNewFile()
        JarOutputStream(patchedJar.outputStream()).use { outJar ->
            JarInputStream(besuJar.inputStream()).use { inJar ->
                val inManifest = inJar.manifest
                if (inManifest != null) {
                    val outManifest = newReproducibleEntry(JarFile.MANIFEST_NAME)
                    outJar.putNextEntry(outManifest)
                    inManifest.write(BufferedOutputStream(outJar))
                    outJar.closeEntry()
                }

                var jarEntry = inJar.nextJarEntry
                while (jarEntry != null) {
                    val name = jarEntry.name
                    var content = inJar.readBytes()

                    // Skip Log4j2Plugins.dat if filtering is enabled
                    if (filterLog4jPlugins && name == LOG4J_PLUGINS_DAT) {
                        jarEntry = inJar.nextJarEntry
                        continue
                    }

                    if (name.startsWith("lib/")) {
                        // All native lib files are moved from 'lib' to 'lib-native'
                        jarEntry = newReproducibleEntry(name.replaceFirst("lib/", "lib-native/"))
                    }
                    if (name.equals(BESU_NATIVE_LIBRARY_LOADER_CLASS)) {
                        // The library loader is replaced with our own version
                        content =
                            BesuNativePatchTransform::class
                                .java
                                .getResourceAsStream("/$BESU_NATIVE_LIBRARY_LOADER_CLASS")!!
                                .readBytes()
                    }

                    jarEntry.setCompressedSize(-1)
                    outJar.putNextEntry(jarEntry)
                    outJar.write(content)
                    outJar.closeEntry()

                    jarEntry = inJar.nextJarEntry
                }
            }
        }
    }

    private fun newReproducibleEntry(name: String) =
        JarEntry(name).also {
            it.time = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis()
        }
}
