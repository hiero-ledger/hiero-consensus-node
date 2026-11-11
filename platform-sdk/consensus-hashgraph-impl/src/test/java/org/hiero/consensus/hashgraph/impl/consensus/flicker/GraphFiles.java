// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParseException;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.GraphParser;

/**
 * Loads the {@code .graph} files in this package's test resources.
 */
final class GraphFiles {

    private GraphFiles() {}

    /**
     * Load and parse a graph file.
     *
     * @param name the file name, such as {@code scn-001.graph}
     * @return the parsed graph
     * @throws IllegalArgumentException if there is no file with that name in this package's resources
     * @throws GraphParseException      if the file is not a well-formed graph
     */
    @NonNull
    static FlickerGraph load(@NonNull final String name) {
        try (final InputStream in = GraphFiles.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no graph file named '%s' in the resources of package %s"
                        .formatted(name, GraphFiles.class.getPackageName()));
            }
            return GraphParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read graph file '%s'".formatted(name), e);
        }
    }
}
