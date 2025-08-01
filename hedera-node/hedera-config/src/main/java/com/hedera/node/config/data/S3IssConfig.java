// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the Uploading of ISS Blocks to an S3 Bucket.
 */
@ConfigData("s3IssConfig")
public record S3IssConfig(
        @ConfigProperty(defaultValue = "false") @NodeProperty boolean enabled,
        @ConfigProperty(defaultValue = "us-east-1") @NodeProperty String regionName,
        @ConfigProperty(defaultValue = "") @NodeProperty String endpointUrl,
        @ConfigProperty(defaultValue = "") @NodeProperty String bucketName,
        @ConfigProperty(defaultValue = "") @NodeProperty String accessKey,
        @ConfigProperty(defaultValue = "") @NodeProperty String secretKey,
        @ConfigProperty(defaultValue = "blocks") @NodeProperty String basePath,
        @ConfigProperty(defaultValue = "STANDARD") @NodeProperty String storageClass) {}
