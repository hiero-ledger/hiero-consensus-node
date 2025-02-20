// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.nio.file.Path;

/**
 *
 * @param savedStateDirectory           The directory where states are saved. This is relative to the current working
 *                                      directory, unless the provided path begins with "/", in which case it will be
 *                                      interpreted as an absolute path.
 */
@ConfigData("state")
public record StateCommonConfig(@ConfigProperty(defaultValue = "data/saved") Path savedStateDirectory) {}
