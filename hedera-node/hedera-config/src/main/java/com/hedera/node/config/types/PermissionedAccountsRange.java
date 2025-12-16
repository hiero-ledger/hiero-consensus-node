// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public class PermissionedAccountsRange {
    private static final Pattern DEGENERATE = Pattern.compile("\\d+");
    private static final Pattern NON_DEGENERATE = Pattern.compile("(\\d+)-(\\d+)");
    private static final Pattern NON_DEGENERATE_WILDCARD = Pattern.compile("(\\d+)-[*]");
    private static final Pattern INCLUSION_LIST = Pattern.compile("(\\d+(?:-\\d+|-\\*)?(?:,\\d+(?:-\\d+|-\\*)?)*)");

    final List<Range> allowedRanges;

    /**
     * A class that represents a single range
     */
    public static class Range {
        final Long from;
        final Long inclusiveTo;

        public Range(long from, long to) {
            this.from = from;
            this.inclusiveTo = to;
        }

        public Range(Long from) {
            this.from = from;
            this.inclusiveTo = null;
        }

        private Long from() {
            return from;
        }

        private Long inclusiveTo() {
            return inclusiveTo;
        }

        private boolean contains(long num) {
            if (inclusiveTo == null) {
                return num == from;
            } else {
                return from <= num && num <= inclusiveTo;
            }
        }

        @Override
        public String toString() {
            return Objects.equals(from, inclusiveTo) ? String.valueOf(from) : from + "-" + inclusiveTo;
        }
    }

    public static PermissionedAccountsRange from(String description) {
        if (StringUtils.isEmpty(description)) {
            return null;
        }

        // First, try to parse as inclusion list syntax (comma-separated ranges)
        final Matcher inclusionListOfRanges = INCLUSION_LIST.matcher(description);
        if (inclusionListOfRanges.matches()) {
            List<Range> allowedRanges = new ArrayList<>();
            String[] parts = description.split(",");

            for (String part : parts) {
                part = part.trim();
                if (part.contains("-")) {
                    // Check for wildcard range
                    if (part.endsWith("-*")) {
                        try {
                            long from = Long.parseLong(
                                    part.substring(0, part.length() - 2).trim());
                            allowedRanges.add(new Range(from, Long.MAX_VALUE));
                        } catch (NumberFormatException e) {
                            // Invalid number, skip
                        }
                    } else {
                        // It's a regular range like "2-10"
                        String[] rangeParts = part.split("-");
                        if (rangeParts.length == 2) {
                            try {
                                final Long from = Long.parseLong(rangeParts[0].trim());
                                final Long to = Long.parseLong(rangeParts[1].trim());
                                if (from < to) {
                                    allowedRanges.add(new Range(from, to));
                                } else if (from.equals(to)) {
                                    allowedRanges.add(new Range(from));
                                }
                            } catch (NumberFormatException e) {
                                // Invalid number, skip
                            }
                        }
                    }
                } else {
                    // It's a single number like "5"
                    try {
                        allowedRanges.add(new Range(Long.parseLong(part.trim())));
                    } catch (NumberFormatException e) {
                        // Invalid number, skip
                    }
                }
            }

            if (!allowedRanges.isEmpty()) {
                return new PermissionedAccountsRange(allowedRanges);
            }
        }

        // Fall back to individual patterns
        final Matcher degenMatch = DEGENERATE.matcher(description);
        if (degenMatch.matches()) {
            List<Range> allowedRanges = List.of(new Range(Long.parseLong(description)));
            return new PermissionedAccountsRange(allowedRanges);
        }

        final Matcher nonDegenMatch = NON_DEGENERATE.matcher(description);
        if (nonDegenMatch.matches()) {
            final Long supposedFrom = Long.valueOf(nonDegenMatch.group(1));
            final Long supposedTo = Long.valueOf(nonDegenMatch.group(2));
            if (supposedFrom < supposedTo) {
                return new PermissionedAccountsRange(List.of(new Range(supposedFrom, supposedTo)));
            } else if (supposedFrom.equals(supposedTo)) {
                return new PermissionedAccountsRange(List.of(new Range(supposedFrom)));
            }
        }

        final Matcher nonDegenWildMatch = NON_DEGENERATE_WILDCARD.matcher(description);
        if (nonDegenWildMatch.matches()) {
            return new PermissionedAccountsRange(
                    List.of(new Range(Long.parseLong(nonDegenWildMatch.group(1)), Long.MAX_VALUE)));
        }

        return null;
    }

    public PermissionedAccountsRange(List<Range> allowedRanges) {
        this.allowedRanges = List.copyOf(allowedRanges);
    }

    public Long from() {
        return allowedRanges.getFirst().from();
    }

    public Long inclusiveTo() {
        return allowedRanges.getLast().inclusiveTo();
    }

    public boolean contains(long num) {
        // Check if the number falls within any of the allowed ranges
        for (Range range : allowedRanges) {
            if (range.contains(num)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return allowedRanges.stream()
                .map(Range::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
