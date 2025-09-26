// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the Uploading of ISS Blocks to an S3 Bucket.
 */
@ConfigData("s3Config")
public record S3Config(
        @ConfigProperty(defaultValue = "false") @NodeProperty boolean enabled,
        @ConfigProperty(defaultValue = "us-east-1") @NodeProperty String regionName,
        @ConfigProperty(defaultValue = "STANDARD") @NodeProperty String storageClass,
        @ConfigProperty(defaultValue = "") @NodeProperty String endpointUrl,
        @ConfigProperty(defaultValue = "") @NodeProperty String bucketName,
        @ConfigProperty(defaultValue = "") @NodeProperty String accessKey,
        @ConfigProperty(defaultValue = "") @NodeProperty String secretKey) {}
