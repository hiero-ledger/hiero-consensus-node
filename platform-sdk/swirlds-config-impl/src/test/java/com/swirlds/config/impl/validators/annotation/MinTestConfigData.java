// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.validation.annotation.Min;

@ConfigData("min")
public record MinTestConfigData(
        @Min(2) int intValue, @Min(2) long longValue, @Min(2) short shortValue, @Min(2) byte byteValue) {}
