// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.validation.annotation.Negative;

@ConfigData("negative")
public record NegativeConfigData(
        @Negative int intValue,
        @Negative long longValue,
        @Negative double doubleValue,
        @Negative float floatValue,
        @Negative short shortValue,
        @Negative byte byteValue) {}
