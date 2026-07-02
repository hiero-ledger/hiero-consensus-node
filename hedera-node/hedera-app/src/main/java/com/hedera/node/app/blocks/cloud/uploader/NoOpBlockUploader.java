// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link BlockUploader} that uploads nothing. Used when both ISS block-upload paths are disabled, so the rest of the
 * pipeline can run unconditionally without special-casing the disabled path.
 */
public class NoOpBlockUploader implements BlockUploader {
    @Override
    @NonNull
    public List<String> uploadBlockFiles(
            @NonNull final UploadCategory category,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> contentsFiles) {
        return List.of();
    }
}
