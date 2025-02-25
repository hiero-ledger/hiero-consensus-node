// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;

@ConfigData("method")
public record BrokenConstraintMethodConfigData(@ConstraintMethod("check") boolean value) {

    public ConfigViolation check() {
        return null;
    }
}
