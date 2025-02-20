// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.json;

import static com.swirlds.logging.legacy.payload.AbstractLogPayload.extractPayloadType;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A filter that acts on payload types.
 */
public class PayloadTypeFilter implements Predicate<JsonLogEntry> {

    private final Set<String> types;

    public static PayloadTypeFilter payloadType(String... types) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, types);
        return new PayloadTypeFilter(set);
    }

    public static PayloadTypeFilter payloadType(List<String> types) {
        return new PayloadTypeFilter(new HashSet<>(types));
    }

    public static PayloadTypeFilter payloadType(Set<String> types) {
        return new PayloadTypeFilter(types);
    }

    /**
     * Create a filter that allows entries with certain types of payloads pass.
     *
     * @param types
     * 		a set of fully qualified payload type names
     */
    public PayloadTypeFilter(Set<String> types) {
        this.types = types;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final JsonLogEntry entry) {
        if (types == null) {
            return false;
        }
        final String type = extractPayloadType(entry.getRawPayload());
        return types.stream().anyMatch(type::contains);
    }
}
