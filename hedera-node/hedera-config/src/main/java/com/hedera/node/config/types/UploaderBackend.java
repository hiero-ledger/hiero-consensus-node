// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

/**
 * The implementation used to upload ISS block files to a cloud bucket. The upload behaviour is hidden behind a single
 * {@code BlockUploader} interface so the backend can be swapped without affecting the rest of the ISS-upload pipeline.
 */
public enum UploaderBackend {
    /**
     * The {@code com.hedera.bucky} pure-Java S3 client (AWS Signature V4); reaches GCS via its XML API interoperability
     * endpoint. This is the default and only fully-supported backend.
     */
    BUCKY,
    /**
     * Shell out to the {@code gcloud storage cp} CLI. Requires the {@code gcloud} CLI installed and authenticated on the
     * node; GCP-only. Not implemented yet.
     */
    GCLOUD_CLI,
    /**
     * A hand-rolled uploader built on {@code java.net.http}. Not implemented yet.
     */
    HTTP
}
