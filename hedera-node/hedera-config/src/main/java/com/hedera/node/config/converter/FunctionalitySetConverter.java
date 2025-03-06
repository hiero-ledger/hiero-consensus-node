// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Configuration converter for a {@link Set} of {@link HederaFunctionality} entries.
 * The converter expects a string containing a comma-separated list of names and converts each
 * name to the corresponding HederaFunctionality.  If there are duplicates, or if any name is not
 * a valid HederaFunctionality, then an exception is thrown.
 */
public class FunctionalitySetConverter implements ConfigConverter<HederaFunctionalitySet> {

    private static final String PATTERN = Pattern.quote(",");

    @NonNull
    @Override
    public HederaFunctionalitySet convert(@NonNull final String value) throws IllegalArgumentException {
        final String[] entries = value.split(PATTERN, 0);
        if (entries == null || entries.length < 1) {
            throw new IllegalArgumentException("Invalid HederaFunctionality Set: %s.".formatted(value));
        }
        Set<HederaFunctionality> conversions = new TreeSet<>();
        for (final String entry : entries) {
            if (!conversions.add(HederaFunctionality.fromString(entry))) {
                throw new IllegalArgumentException("Invalid HederaFunctionality name: %s.".formatted(entry));
            }
        }
        return new HederaFunctionalitySet(conversions);
    }
}
