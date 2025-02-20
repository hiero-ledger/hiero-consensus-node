// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.lang3.builder.CompareToBuilder;

public record AllowanceId(Long tokenNum, Long spenderNum) implements Comparable<AllowanceId> {
    @Override
    public int compareTo(@NonNull final AllowanceId that) {
        return new CompareToBuilder()
                .append(tokenNum, that.tokenNum)
                .append(spenderNum, that.spenderNum)
                .toComparison();
    }
}
