// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for the free disk space check done before each round. Read once at startup from the bootstrap
 * config, so file 0.0.121 cannot change it.
 *
 * @param reservedMb the free space, in MiB, that every data volume of the node must keep; while any volume has less,
 *                   round handling pauses; zero disables the check
 * @param recheckInterval how often to check free space again while round handling is paused
 */
@ConfigData("diskSpace")
public record DiskSpaceConfig(
        @ConfigProperty(defaultValue = "2048") @Min(0) @NodeProperty
        int reservedMb,

        @ConfigProperty(defaultValue = "1s") @Min(1) @NodeProperty
        Duration recheckInterval) {}
