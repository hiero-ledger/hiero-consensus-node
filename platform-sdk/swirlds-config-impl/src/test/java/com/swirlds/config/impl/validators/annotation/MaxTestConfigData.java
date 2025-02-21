// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.validation.annotation.Max;

@ConfigData("max")
public record MaxTestConfigData(
        @Max(2) int intValue, @Max(2) long longValue, @Max(2) short shortValue, @Max(2) byte byteValue) {}
