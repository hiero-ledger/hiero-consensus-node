// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;

/**
 * Uploads the block files flushed for ISS triage to a cloud bucket.
 *
 * <p>This is the single swappable seam over the chosen storage backend; the rest of the
 * ISS-upload pipeline ({@code IssBlockUploadCoordinator}) depends only on this interface. Implementations must be
 * best-effort and must not throw: the caller runs on the node's catastrophic-shutdown path, so an upload failure must
 * never prevent the orderly halt.
 */
public interface BlockUploader {
    /**
     * Uploads the given block-contents files (the {@code .iss.gz}/{@code .pnd.gz} files the triage flush produced).
     * For a pending block ({@code .pnd.gz}), the sibling {@code .pnd.json} proof file is uploaded alongside it when
     * present. Each file becomes a separate object, grouped under a per-incident folder so the blocks from one
     * catastrophic failure are kept together and never intermix with a later failure's.
     *
     * @param incidentFolder a per-incident key segment (e.g. the failure timestamp) under which all of this upload's
     * objects are placed
     * @param contentsFiles the block-contents files to upload
     * @return the remote object URIs actually written; empty if nothing was uploaded (disabled, no files, or failure)
     */
    @NonNull
    List<String> uploadBlockFiles(@NonNull String incidentFolder, @NonNull List<Path> contentsFiles);
}
