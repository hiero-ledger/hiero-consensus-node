// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test.fixtures;
// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigData;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods for working with configuration.
 */
public final class ConfigUtils {

    private ConfigUtils() {}

    /**
     * Scan all classes on the classpath / modulepath that are under the provided {@code packagePrefixes}.
     * If the given {@code packagePrefixes} Set is empty all packages will be scanned.
     *
     * @param packagePrefixes      the package prefixes to scan
     * @return the set of all configuration data types found
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static Set<Class<? extends Record>> loadAllConfigDataRecords(@NonNull final Set<String> packagePrefixes) {
        Objects.requireNonNull(packagePrefixes, "packagePrefix must not be null");

        final ClassGraph classGraph = new ClassGraph().enableAnnotationInfo();

        if (!packagePrefixes.isEmpty()) {
            classGraph.whitelistPackages(packagePrefixes.toArray(new String[0]));
        }

        try (final ScanResult result = classGraph.scan()) {
            final ClassInfoList classInfos = result.getClassesWithAnnotation(ConfigData.class.getName());
            return classInfos.stream()
                    .map(ClassInfo::loadClass)
                    .filter(Class::isRecord)
                    .map(clazz -> (Class<? extends Record>) clazz)
                    .collect(Collectors.toSet());
        }
    }
}
