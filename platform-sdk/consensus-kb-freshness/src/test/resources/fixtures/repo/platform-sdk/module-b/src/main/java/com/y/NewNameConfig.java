// SPDX-License-Identifier: Apache-2.0
package com.y;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("fix.b")
public record NewNameConfig(@ConfigProperty(defaultValue = "1") int one) {}
