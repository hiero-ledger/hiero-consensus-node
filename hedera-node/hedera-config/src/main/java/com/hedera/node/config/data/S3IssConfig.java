// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration for the Uploading of ISS Blocks to an S3 Bucket.
 */
@ConfigData("s3IssConfig")
public record S3IssConfig(
        @ConfigProperty(defaultValue = "blocks") @NodeProperty String basePath,
        @ConfigProperty(defaultValue = "output/iss/") @NodeProperty String diskPath,
        @ConfigProperty(defaultValue = "5") @Min(5) @Max(10) @NodeProperty int recordBlockBufferSize) {}
