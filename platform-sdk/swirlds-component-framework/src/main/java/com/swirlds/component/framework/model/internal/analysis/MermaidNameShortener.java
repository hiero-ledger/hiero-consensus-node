// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates shortened names in a mermaid wiring flowchart. Reduces the resulting flowchart size.
 */
public class MermaidNameShortener {

    private final Map<String, String> vertexNameMap = new HashMap<>();

    /**
     * Get the name that should be used for a vertex with the given base name.
     *
     * @param baseName the base name of the vertex
     * @return the name that should be used for a vertex with the given base name
     */
    @NonNull
    public String getShortVertexName(@NonNull final String baseName) {
        return vertexNameMap.computeIfAbsent(baseName, this::generateShortVertexName);
    }

    /**
     * Generate a name for a vertex with the given base name.
     *
     * @param baseName the base name of the vertex
     * @return the name that should be used for a vertex with the given base name
     */
    @NonNull
    private String generateShortVertexName(@NonNull final String baseName) {
        return "v" + vertexNameMap.size();
    }
}
