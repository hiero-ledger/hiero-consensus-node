// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.ConfigComponent;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;
import org.hiero.consensus.kbfreshness.util.RepoPaths;

/**
 * A deterministic scan of every indexed {@code @ConfigData} record. The scan covers indexed
 * {@code *Config.java} files (the repo's config-record naming convention) under {@code src/main/java}
 * trees only — a fixture or test-resource copy of a config record must never masquerade as a real one.
 * Shared by the tunables prefix resolution, the undocumented-record coverage check, and the gone-key
 * did-you-mean hints, so all three see the same record set.
 */
public final class ConfigRecords {

    /**
     * One indexed config record: where it lives and its parsed view.
     *
     * @param path      the repo-relative source path.
     * @param className the record's simple name.
     * @param type      the parsed type info (carries prefix and components).
     */
    public record Owner(String path, String className, TypeInfo type) {

        /**
         * The module directory of this record's path (the segment preceding the first {@code src}).
         *
         * @return the module name, or {@code null} if the path has no {@code src} segment.
         */
        public String module() {
            return RepoPaths.moduleOf(path);
        }
    }

    /** Prevents instantiation of this static-only scanner. */
    private ConfigRecords() {}

    /**
     * Scans the index for every {@code @ConfigData}-annotated type declared in a {@code *Config.java}
     * file directly under its module's {@code src/main/java} tree, in deterministic (basename, path)
     * order.
     *
     * @param index the source index to scan.
     * @return the discovered config records.
     */
    public static List<Owner> scan(final SourceIndex index) {
        final List<Owner> owners = new ArrayList<>();
        for (final String basename : index.basenames()) {
            if (!basename.endsWith("Config.java")) {
                continue;
            }
            for (final String path : index.pathsForBasename(basename)) {
                if (!isMainSource(path)) {
                    continue;
                }
                for (final Map.Entry<String, TypeInfo> e :
                        index.parse(path).types().entrySet()) {
                    if (e.getValue().configPrefix() != null) {
                        owners.add(new Owner(path, e.getKey(), e.getValue()));
                    }
                }
            }
        }
        return owners;
    }

    /**
     * The indexed config records that declare a component whose bound key name equals the gone key's bare
     * property name, excluding the record that declares exactly the gone key itself. This is the shared
     * "same-named key" scan behind both the report's key-migration rollup (which asserts a migration only
     * when this returns exactly one record) and the suggestions' key-migration hints (which offer all of
     * them, alongside its own similar-name matches). One entry is returned per matching component, so two
     * records declaring the same-named key read as two — the ambiguity the report needs to see.
     *
     * @param owners  every indexed config record.
     * @param goneKey the fully-qualified documented key that is gone.
     * @return the records exact-declaring the same bare key name, one entry per matching component.
     */
    public static List<Owner> declaringRecordsOf(final List<Owner> owners, final String goneKey) {
        final int dot = goneKey.lastIndexOf('.');
        final String goneProp = dot >= 0 ? goneKey.substring(dot + 1) : goneKey;
        final List<Owner> declaring = new ArrayList<>();
        for (final Owner owner : owners) {
            for (final ConfigComponent c : owner.type().configComponents()) {
                if (c.keyName().equals(goneProp)
                        && !owner.type().fullyQualifiedKey(c.keyName()).equals(goneKey)) {
                    declaring.add(owner);
                }
            }
        }
        return declaring;
    }

    /**
     * Whether a path lies directly under its module's {@code src/main/java} tree. The index only walks
     * {@code src/main/java} trees, but a test-resource fixture can embed one (e.g.
     * {@code <module>/src/test/resources/…/src/main/java/…}) — the first {@code src} segment after the
     * module must already be the {@code src/main/java} one.
     *
     * @param repoRelPath the repo-relative source path.
     * @return {@code true} when the module's own source set is {@code src/main/java}.
     */
    private static boolean isMainSource(final String repoRelPath) {
        final String module = RepoPaths.moduleOf(repoRelPath);
        return module != null && repoRelPath.contains("/" + module + "/src/main/java/");
    }
}
