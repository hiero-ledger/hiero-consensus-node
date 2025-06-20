// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * The version information is provided as part of the build process and stored in a resource file within the JAR files
 * of the application itself. Previously, these were stored with a slightly different name.
 *
 * @param servicesVersion The version of the services code itself
 * @param hapiVersion The version of the supported HAPI
 */
@ConfigData
public record VersionConfig(
        @ConfigProperty(value = "hedera.services.version", defaultValue = "0.0.0") @NetworkProperty
                SemanticVersion servicesVersion,
        @ConfigProperty(value = "hapi.proto.version", defaultValue = "0.0.0") @NetworkProperty
                SemanticVersion hapiVersion) {}
