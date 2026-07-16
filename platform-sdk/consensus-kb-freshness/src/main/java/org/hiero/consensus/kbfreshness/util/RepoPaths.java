// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.util;

import java.nio.file.Path;

/**
 * Stateless helpers for the repo-relative, forward-slashed paths the checker works in (module directory,
 * last segment, class name, parent, first-segment strip, relative resolution). Shared across extraction,
 * resolution, and rendering so the same path conventions apply everywhere.
 */
public final class RepoPaths {

    /** Prevents instantiation of this utility class. */
    private RepoPaths() {}

    /**
     * The module directory of a repo-relative path: the segment immediately preceding the first
     * {@code src} segment.
     *
     * @param repoRelPath the repo-relative, forward-slashed path.
     * @return the module directory name, or {@code null} if the path has no {@code src} segment.
     */
    public static String moduleOf(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("src")) {
                return parts[i - 1];
            }
        }
        return null;
    }

    /**
     * The last {@code /}-separated segment of a path, ignoring a single trailing slash.
     *
     * @param path the path.
     * @return the final segment, or the whole (de-slashed) path if it has no slash.
     */
    public static String lastSegment(final String path) {
        final String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        final int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /**
     * A filename with its first extension (and anything after) removed.
     *
     * @param name the filename.
     * @return the name up to the first dot, or the whole name if it has no leading-dot extension.
     */
    public static String stripExtension(final String name) {
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * The simple class name implied by a source path: its last segment with the extension stripped.
     *
     * @param path the source path.
     * @return the class name (last segment, extension removed).
     */
    public static String classNameOfPath(final String path) {
        return stripExtension(lastSegment(path));
    }

    /**
     * The parent directory portion of a repo-relative path.
     *
     * @param repoRelPath the repo-relative path.
     * @return the parent directory, or an empty string if the path has no directory component.
     */
    public static String parentDir(final String repoRelPath) {
        final int slash = repoRelPath.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? repoRelPath.substring(0, slash) : "";
    }

    /**
     * The path with its first {@code /}-separated segment removed (e.g. {@code platform-sdk/m/F.java}
     * to {@code m/F.java}), or {@code null} when the path has no slash.
     *
     * @param path the path.
     * @return the path without its first segment, or {@code null}.
     */
    public static String withoutFirstSegment(final String path) {
        final int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : null;
    }

    /**
     * Resolves {@code rel} against {@code baseDir} (both repo-relative), normalizing {@code ..}.
     *
     * @param baseDir the repo-relative base directory.
     * @param rel     the path to resolve against the base.
     * @return the normalized, forward-slashed repo-relative path.
     */
    public static String resolveRelative(final String baseDir, final String rel) {
        final Path base = baseDir.isEmpty() ? Path.of("") : Path.of(baseDir);
        return base.resolve(rel).normalize().toString().replace('\\', '/');
    }
}
