// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;
import java.util.Set;

@ConfigData("empty")
public record EmptyCollectionConfig(
        @ConfigProperty(defaultValue = "[]") List<Integer> list,
        @ConfigProperty(defaultValue = "[]") Set<Integer> set) {}
