// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public class PermissionedAccountsRange {
    private static final Pattern DEGENERATE = Pattern.compile("\\d+");
    private static final Pattern NON_DEGENERATE = Pattern.compile("(\\d+)-(\\d+)");
    private static final Pattern NON_DEGENERATE_WILDCARD = Pattern.compile("(\\d+)-[*]");
    private static final Pattern NON_DEGENERATE_WITH_EXCLUSION = Pattern.compile("(\\d+)-(\\d+)!(\\d+)-(\\d+)");
    private static final Pattern NON_DEGENERATE_WILDCARD_WITH_EXCLUSION = Pattern.compile("(\\d+)-[*]!(\\d+)-(\\d+)");
    private static final Pattern NON_DEGENERATE_WITH_SINGLE_EXCLUSION = Pattern.compile("(\\d+)-(\\d+)!(\\d+)");
    private static final Pattern NON_DEGENERATE_WILDCARD_WITH_SINGLE_EXCLUSION = Pattern.compile("(\\d+)-[*]!(\\d+)");

    final Long from;
    final Long inclusiveTo;
    final Long excludeFrom;
    final Long excludeInclusiveTo;

    public static PermissionedAccountsRange from(String description) {
        if (StringUtils.isEmpty(description)) {
            return null;
        }

        final Matcher degenMatch = DEGENERATE.matcher(description);
        if (degenMatch.matches()) {
            return new PermissionedAccountsRange(Long.valueOf(description));
        }

        // Check for ranges with exclusions first
        final Matcher exclusionMatch = NON_DEGENERATE_WITH_EXCLUSION.matcher(description);
        if (exclusionMatch.matches()) {
            final Long supposedFrom = Long.valueOf(exclusionMatch.group(1));
            final Long supposedTo = Long.valueOf(exclusionMatch.group(2));
            final Long excludeFrom = Long.valueOf(exclusionMatch.group(3));
            final Long excludeTo = Long.valueOf(exclusionMatch.group(4));
            if (supposedFrom < supposedTo && excludeFrom <= excludeTo) {
                return new PermissionedAccountsRange(supposedFrom, supposedTo, excludeFrom, excludeTo);
            }
        }

        final Matcher wildcardExclusionMatch = NON_DEGENERATE_WILDCARD_WITH_EXCLUSION.matcher(description);
        if (wildcardExclusionMatch.matches()) {
            final Long supposedFrom = Long.valueOf(wildcardExclusionMatch.group(1));
            final Long excludeFrom = Long.valueOf(wildcardExclusionMatch.group(2));
            final Long excludeTo = Long.valueOf(wildcardExclusionMatch.group(3));
            if (excludeFrom <= excludeTo) {
                return new PermissionedAccountsRange(supposedFrom, Long.MAX_VALUE, excludeFrom, excludeTo);
            }
        }

        // Check for ranges with single number exclusions
        final Matcher singleExclusionMatch = NON_DEGENERATE_WITH_SINGLE_EXCLUSION.matcher(description);
        if (singleExclusionMatch.matches()) {
            final Long supposedFrom = Long.valueOf(singleExclusionMatch.group(1));
            final Long supposedTo = Long.valueOf(singleExclusionMatch.group(2));
            final Long excludeNum = Long.valueOf(singleExclusionMatch.group(3));
            if (supposedFrom < supposedTo) {
                return new PermissionedAccountsRange(supposedFrom, supposedTo, excludeNum, excludeNum);
            }
        }

        final Matcher wildcardSingleExclusionMatch = NON_DEGENERATE_WILDCARD_WITH_SINGLE_EXCLUSION.matcher(description);
        if (wildcardSingleExclusionMatch.matches()) {
            final Long supposedFrom = Long.valueOf(wildcardSingleExclusionMatch.group(1));
            final Long excludeNum = Long.valueOf(wildcardSingleExclusionMatch.group(2));
            return new PermissionedAccountsRange(supposedFrom, Long.MAX_VALUE, excludeNum, excludeNum);
        }

        final Matcher nonDegenMatch = NON_DEGENERATE.matcher(description);
        if (nonDegenMatch.matches()) {
            final Long supposedFrom = Long.valueOf(nonDegenMatch.group(1));
            final Long supposedTo = Long.valueOf(nonDegenMatch.group(2));
            if (supposedFrom < supposedTo) {
                return new PermissionedAccountsRange(supposedFrom, supposedTo);
            } else if (supposedFrom.equals(supposedTo)) {
                return new PermissionedAccountsRange(supposedFrom);
            }
        }

        final Matcher nonDegenWildMatch = NON_DEGENERATE_WILDCARD.matcher(description);
        if (nonDegenWildMatch.matches()) {
            return new PermissionedAccountsRange(Long.valueOf(nonDegenWildMatch.group(1)), Long.MAX_VALUE);
        }

        return null;
    }

    public PermissionedAccountsRange(Long from, Long inclusiveTo) {
        this.from = from;
        this.inclusiveTo = inclusiveTo;
        this.excludeFrom = null;
        this.excludeInclusiveTo = null;
    }

    public PermissionedAccountsRange(Long from) {
        this.from = from;
        this.inclusiveTo = null;
        this.excludeFrom = null;
        this.excludeInclusiveTo = null;
    }

    public PermissionedAccountsRange(Long from, Long inclusiveTo, Long excludeFrom, Long excludeInclusiveTo) {
        this.from = from;
        this.inclusiveTo = inclusiveTo;
        this.excludeFrom = excludeFrom;
        this.excludeInclusiveTo = excludeInclusiveTo;
    }

    public Long from() {
        return from;
    }

    public Long inclusiveTo() {
        return inclusiveTo;
    }

    public Long excludeFrom() {
        return excludeFrom;
    }

    public Long excludeInclusiveTo() {
        return excludeInclusiveTo;
    }

    public boolean contains(long num) {
        boolean inRange;
        if (inclusiveTo == null) {
            inRange = num == from;
        } else {
            inRange = from <= num && num <= inclusiveTo;
        }

        // If in range, check if it's excluded
        if (inRange && excludeFrom != null && excludeInclusiveTo != null) {
            // Return true only if the number is outside the exclusion range
            return num < excludeFrom || excludeInclusiveTo < num;
        }

        return inRange;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(from);
        if (inclusiveTo != null) {
            sb.append("-").append(inclusiveTo);
        }
        if (excludeFrom != null && excludeInclusiveTo != null) {
            sb.append("!");
            if (excludeFrom.equals(excludeInclusiveTo)) {
                // Single number exclusion
                sb.append(excludeFrom);
            } else {
                // Range exclusion
                sb.append(excludeFrom).append("-").append(excludeInclusiveTo);
            }
        }
        return sb.toString();
    }
}
