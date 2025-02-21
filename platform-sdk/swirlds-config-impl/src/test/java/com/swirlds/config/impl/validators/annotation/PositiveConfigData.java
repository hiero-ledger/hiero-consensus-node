// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.validation.annotation.Positive;

@ConfigData("positive")
public record PositiveConfigData(
        @Positive int intValue,
        @Positive long longValue,
        @Positive double doubleValue,
        @Positive float floatValue,
        @Positive short shortValue,
        @Positive byte byteValue) {}
