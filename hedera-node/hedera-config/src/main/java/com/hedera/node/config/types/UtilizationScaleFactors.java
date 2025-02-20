// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import java.util.Arrays;

public record UtilizationScaleFactors(int[] usagePercentTriggers, ScaleFactor[] scaleFactors) {
    public static UtilizationScaleFactors from(final String csv) {
        final var triggeredFactors = TriggeredValuesParser.parseFrom(csv, ScaleFactor::from);
        return new UtilizationScaleFactors(
                triggeredFactors.triggers().stream().mapToInt(v -> v).toArray(),
                triggeredFactors.values().stream().toArray(ScaleFactor[]::new));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UtilizationScaleFactors that = (UtilizationScaleFactors) o;
        return Arrays.equals(usagePercentTriggers, that.usagePercentTriggers)
                && Arrays.equals(scaleFactors, that.scaleFactors);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(usagePercentTriggers);
        result = 31 * result + Arrays.hashCode(scaleFactors);
        return result;
    }

    @Override
    public String toString() {
        return "UtilizationScaleFactors{"
                + "usagePercentTriggers="
                + Arrays.toString(usagePercentTriggers)
                + ", scaleFactors="
                + Arrays.toString(scaleFactors)
                + '}';
    }
}
