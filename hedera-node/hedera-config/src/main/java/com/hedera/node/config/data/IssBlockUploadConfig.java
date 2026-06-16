// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.UploaderBackend;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for uploading the block(s) around a self/catastrophic ISS to a cloud bucket for developer triage.
 *
 * <p>All properties are {@link NodeProperty per-node} operational concerns. The bucket access key and secret are
 * deliberately <b>not</b> configured here (they would leak through configuration logging); they are loaded from the
 * credentials file under {@link #credentialsFileDir}/{@link #credentialsFileName}, overridable by the
 * {@code ISS_BUCKET_ACCESS_KEY} / {@code ISS_BUCKET_SECRET_KEY} environment variables.
 *
 * @param enabled whether ISS block upload is active; when {@code false} the node uses a no-op uploader and never
 * requires credentials
 * @param backend the uploader implementation to use (see {@link UploaderBackend})
 * @param bucketName the destination bucket name
 * @param endpoint the storage endpoint; for GCP this is the S3-compatible XML interoperability endpoint
 * ({@code https://storage.googleapis.com}); for AWS S3 the regional endpoint (the provider is determined entirely by
 * this endpoint + region + credentials)
 * @param region the storage region; for GCP interoperability this is typically {@code auto}
 * @param storageClass the object storage class (e.g. {@code STANDARD})
 * @param objectKeyPrefix a prefix prepended to every uploaded object key
 * @param credentialsFileDir the directory containing the bucket credentials file
 * @param credentialsFileName the name of the bucket credentials properties file (keys {@code accessKey} and
 * {@code secretKey})
 * @param uploadTimeout the hard overall deadline for the entire ISS upload; once it elapses the node abandons the
 * upload and continues its shutdown
 * @param maxRetries the maximum number of retries per object on a transient upload failure
 */
@ConfigData("issBlockUpload")
public record IssBlockUploadConfig(
        @ConfigProperty(defaultValue = "false") @NodeProperty
        boolean enabled,

        @ConfigProperty(defaultValue = "BUCKY") @NodeProperty
        UploaderBackend backend,

        @ConfigProperty(defaultValue = "") @NodeProperty String bucketName,

        @ConfigProperty(defaultValue = "https://storage.googleapis.com") @NodeProperty
        String endpoint,

        @ConfigProperty(defaultValue = "auto") @NodeProperty String region,

        @ConfigProperty(defaultValue = "STANDARD") @NodeProperty
        String storageClass,

        @ConfigProperty(defaultValue = "iss-blocks") @NodeProperty
        String objectKeyPrefix,

        @ConfigProperty(defaultValue = "data/config") @NodeProperty
        String credentialsFileDir,

        @ConfigProperty(defaultValue = "iss-bucket-credentials.properties") @NodeProperty
        String credentialsFileName,

        @ConfigProperty(defaultValue = "60s") @NodeProperty Duration uploadTimeout,

        @ConfigProperty(defaultValue = "3") @Min(0) @NodeProperty
        int maxRetries) {}
