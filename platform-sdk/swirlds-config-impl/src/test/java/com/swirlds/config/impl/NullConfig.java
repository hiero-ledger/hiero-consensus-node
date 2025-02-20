// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static com.swirlds.config.api.ConfigProperty.NULL_DEFAULT_VALUE;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;
import java.util.Set;

@ConfigData("null")
public record NullConfig(
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) List<Integer> list,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) Set<Integer> set,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) String value) {}
