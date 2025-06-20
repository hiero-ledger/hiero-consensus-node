// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.records;

import com.hedera.hapi.node.base.FileID;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CreateFile} transaction.
 */
public interface CreateFileStreamBuilder extends StreamBuilder {

    /**
     * Tracks creation of a new file by {@link FileID}.
     *
     * @param fileID the {@link FileID} of the new file
     * @return this builder
     */
    @NonNull
    CreateFileStreamBuilder fileID(@NonNull FileID fileID);
}
