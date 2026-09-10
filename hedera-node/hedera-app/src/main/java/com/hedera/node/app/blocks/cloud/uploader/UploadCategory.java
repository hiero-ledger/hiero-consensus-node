// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

/**
 * The top-level bucket folder a block upload belongs to. One bucket holds both categories so an operator can correlate
 * a detection-time capture with its catastrophic-failure flush, kept apart by this leading key segment.
 */
public enum UploadCategory {
    /** The exact ISS-round block located and captured at detection time. */
    ISS("iss"),
    /** The open/pending blocks flushed to disk at catastrophic failure for triage. */
    TRIAGE("triage");

    private final String segment;

    UploadCategory(final String segment) {
        this.segment = segment;
    }

    /** The object-key path segment for this category (e.g. {@code iss} or {@code triage}). */
    public String segment() {
        return segment;
    }
}
