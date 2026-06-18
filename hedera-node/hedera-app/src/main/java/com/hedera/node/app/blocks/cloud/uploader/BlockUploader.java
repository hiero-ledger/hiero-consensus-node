// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;

/**
 * Uploads ISS-related block files to a cloud bucket.
 *
 * <p>This is the single swappable seam over the chosen storage backend; the rest of the
 * ISS-upload pipeline (the detection and triage coordinators) depends only on this interface. Implementations must be
 * best-effort and must not throw: a caller may run on the node's catastrophic-shutdown path, so an upload failure must
 * never prevent the orderly halt.
 */
public interface BlockUploader {
    /**
     * Uploads the given block-contents files (the {@code .iss.gz}/{@code .pnd.gz}/{@code .open.gz} files captured for
     * triage). For a pending block ({@code .pnd.gz}), the sibling {@code .pnd.json} proof file is uploaded alongside it
     * when present. Each file becomes a separate object, placed under the given {@code category} folder and grouped
     * under a per-incident folder so the blocks from one event are kept together and never intermix with another's.
     *
     * @param category the top-level bucket folder for this upload ({@code iss/} for a detection-time capture,
     * {@code triage/} for the catastrophic-failure flushed set)
     * @param incidentFolder a per-incident key segment (e.g. the event timestamp) under which all of this upload's
     * objects are placed
     * @param contentsFiles the block-contents files to upload
     * @return the remote object URIs actually written; empty if nothing was uploaded (disabled, no files, or failure)
     */
    @NonNull
    List<String> uploadBlockFiles(
            @NonNull UploadCategory category, @NonNull String incidentFolder, @NonNull List<Path> contentsFiles);
}
