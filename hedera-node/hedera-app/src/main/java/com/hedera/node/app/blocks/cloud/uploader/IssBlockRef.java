// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * A single block to be uploaded for ISS triage, with all of its on-disk artifacts.
 *
 * @param issType the ISS type that triggered the upload (used in the object key)
 * @param round the ISS round (used in the object key); for a preceding context block this is still the ISS round
 * @param blockNumber the block number of this block
 * @param files the block's on-disk files: the contents file ({@code .blk.gz}/{@code .pnd.gz}/{@code .open.gz}) plus,
 * for a pending block, its {@code .pnd.json} proof sidecar
 */
public record IssBlockRef(
        @NonNull IssType issType,
        long round,
        long blockNumber,
        @NonNull List<Path> files) {
    public IssBlockRef {
        requireNonNull(issType);
        files = List.copyOf(requireNonNull(files));
    }
}
