// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.WrappedRecordFileBlockHashes;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.records.BlockRecordService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the schema for storing wrapped record-file block hashes in a deterministic queue state.
 */
public class V0720BlockRecordSchema extends Schema<SemanticVersion> {

    public static final String WRAPPED_RECORD_FILE_BLOCK_HASHES_KEY = "WRAPPED_RECORD_FILE_BLOCK_HASHES";

    public static final int WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_WRAPPED_RECORD_FILE_BLOCK_HASHES.protoOrdinal();

    @SuppressWarnings("unused")
    public static final String WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_LABEL =
            computeLabel(BlockRecordService.NAME, WRAPPED_RECORD_FILE_BLOCK_HASHES_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(72).patch(0).build();

    public V0720BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(
                WRAPPED_RECORD_FILE_BLOCK_HASHES_STATE_ID,
                WRAPPED_RECORD_FILE_BLOCK_HASHES_KEY,
                WrappedRecordFileBlockHashes.PROTOBUF));
    }
}
