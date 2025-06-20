// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.impl.validators.DefaultConfigViolation;

@ConfigData("method")
public record ConstraintMethodConfigData(
        @ConstraintMethod("checkA") boolean valueA, @ConstraintMethod("checkB") boolean valueB) {

    public ConfigViolation checkA(final Configuration configuration) {
        if (valueA) {
            return null;
        }
        return new DefaultConfigViolation("method.checkA", valueA + "", true, "error");
    }

    public ConfigViolation checkB(final Configuration configuration) {
        if (valueB) {
            return null;
        }
        throw new RuntimeException("Error in validation");
    }
}
