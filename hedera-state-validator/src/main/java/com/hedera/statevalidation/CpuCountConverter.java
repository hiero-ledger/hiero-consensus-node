// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import picocli.CommandLine;

/**
 * Picocli converter that parses an integer or a percentage string (e.g. "50%") into a CPU count.
 * Percent values are computed against Runtime.getRuntime().availableProcessors().
 */
public final class CpuCountConverter implements CommandLine.ITypeConverter<Integer> {

    @Override
    public Integer convert(final String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            throw new CommandLine.TypeConversionException("Thread count must not be blank");
        }
        final String v = value.trim();
        final int available = Runtime.getRuntime().availableProcessors();
        try {
            if (v.endsWith("%")) {
                final String num = v.substring(0, v.length() - 1).trim();
                final double pct = Double.parseDouble(num);
                if (pct < 0.0) {
                    throw new CommandLine.TypeConversionException("Percent thread value must be >= 0: " + value);
                }
                // Compute conservative thread count: floor of (available * pct/100), but at least 1 and at most
                // available
                int computed = (int) Math.floor(available * pct / 100.0);
                if (computed < 1) {
                    computed = 1;
                }

                return computed;
            }

            final int parsed = Integer.parseInt(v);
            if (parsed <= 0) {
                throw new CommandLine.TypeConversionException("Thread count must be positive: " + value);
            }
            return parsed;
        } catch (final NumberFormatException ex) {
            // Picocli's TypeConversionException only has a constructor taking a message string, so
            // wrap the parse error with a message and rethrow using the single-arg constructor.
            throw new CommandLine.TypeConversionException("Invalid thread count: " + value);
        }
    }
}
