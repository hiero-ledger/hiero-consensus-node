// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.tss.TssMessageMapKey;
import com.hedera.hapi.node.state.tss.TssVoteMapKey;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema for the TSS service.
 */
@Deprecated(forRemoval = true, since = "0.59.0")
public class V0560TssBaseSchema extends Schema {
    public static final String TSS_MESSAGE_MAP_KEY = "TSS_MESSAGES";
    public static final String TSS_VOTE_MAP_KEY = "TSS_VOTES";
    /**
     * This will at most be equal to the number of nodes in the network.
     */
    private static final long MAX_TSS_MESSAGES = 65_536L;
    /**
     * This will at most be equal to the number of nodes in the network.
     */
    private static final long MAX_TSS_VOTES = 65_536L;

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(56).patch(0).build();
    /**
     * Create a new instance
     */
    public V0560TssBaseSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.onDisk(
                        TSS_MESSAGE_MAP_KEY,
                        TssMessageMapKey.PROTOBUF,
                        TssMessageTransactionBody.PROTOBUF,
                        MAX_TSS_MESSAGES),
                StateDefinition.onDisk(
                        TSS_VOTE_MAP_KEY, TssVoteMapKey.PROTOBUF, TssVoteTransactionBody.PROTOBUF, MAX_TSS_VOTES));
    }
}
