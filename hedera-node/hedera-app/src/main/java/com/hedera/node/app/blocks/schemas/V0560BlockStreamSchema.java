// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.blocks.BlockStreamService;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the schema for state with two notable properties:
 * <ol>
 *     <li>It is needed for a new or reconnected node to construct the next block exactly as will
 *     nodes already in the network.</li>
 *     <li>It is derived from the block stream, and hence the natural provenance of the same service
 *     that is managing and producing blocks.</li>
 * </ol>
 * <p>
 * The particular items with these properties are,
 * <ol>
 *     <li>The <b>number of the last completed block</b>, which each node must increment in the next block.</li>
 *     <li>The <b>first consensus time of the last finished block</b>, for comparison with the consensus
 *     time at the start of the current block. Depending on the elapsed period between these times,
 *     the network may deterministically choose to purge expired entities, adjust node stakes and
 *     reward rates, or take other actions.</li>
 *     <li>The <b>last four values of the input block item running hash</b>, used to generate pseudorandom
 *     values for the {@link com.hedera.hapi.node.base.HederaFunctionality#UTIL_PRNG} operation.</li>
 *     <li>The <b>trailing 256 block hashes</b>, used to implement the EVM {@code BLOCKHASH} opcode.</li>
 * </ol>
 */
public class V0560BlockStreamSchema extends Schema<SemanticVersion> {
    public static final String BLOCK_STREAM_INFO_KEY = "BLOCK_STREAM_INFO";
    public static final int BLOCK_STREAM_INFO_STATE_ID =
            SingletonType.BLOCKSTREAMSERVICE_I_BLOCK_STREAM_INFO.protoOrdinal();
    public static final String BLOCK_STREAM_INFO_STATE_LABEL =
            computeLabel(BlockStreamService.NAME, BLOCK_STREAM_INFO_KEY);

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(56).patch(0).build();

    /**
     * Schema constructor.
     */
    public V0560BlockStreamSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate(@NonNull final Configuration config) {
        return Set.of(
                StateDefinition.singleton(BLOCK_STREAM_INFO_STATE_ID, BLOCK_STREAM_INFO_KEY, BlockStreamInfo.PROTOBUF));
    }
}
