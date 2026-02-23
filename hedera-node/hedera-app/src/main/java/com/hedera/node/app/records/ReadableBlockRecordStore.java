// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ReadableBlockRecordStore {

    /** The underlying data storage class that holds the block info data. */
    private final ReadableSingletonState<BlockInfo> blockInfo;

    public ReadableBlockRecordStore(@NonNull final ReadableStates states) {
        this.blockInfo = requireNonNull(states.getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID));
    }

    /**
     * Returns information about the currently-ongoing and latest completed record blocks
     */
    @NonNull
    public BlockInfo getLastBlockInfo() {
        return blockInfo.get();
    }
}
