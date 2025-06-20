// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import java.util.Objects;

/**
 * Converter that ensures a label satisfies Prometheus requirements
 * (only alphanumeric characters, '_', and ':')
 */
public final class NameConverter {

    private NameConverter() {}

    /**
     * Returns a {@link String} where all illegal characters are replaced (according to a simple heuristics).
     *
     * @param label
     * 		The input-{@link String}
     * @return The resulting {@link String}
     * @throws NullPointerException in case {@code label} parameter is {@code null}
     */
    public static String fix(final String label) {
        Objects.requireNonNull(label, "label must not be null");
        return label.strip()
                .replace('.', ':')
                .replace('-', '_')
                .replace(' ', '_')
                .replace("/", "_per_")
                .replace("%", "Percent")
                .replaceAll("[^\\w:]", "");
    }
}
