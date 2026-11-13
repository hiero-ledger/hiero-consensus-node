// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Adds the CLPR channel index: every Channel that is neither {@code PENDING} nor {@code CLOSED} gets a sequence
 * number, mapped to its id in the channel index entries; and the channel index singleton holds the last sequence
 * number issued. The {@code CHANNELS} key/value state cannot be enumerated, so a node reads the entries up to that
 * sequence number when it starts to know which Channels to sync.
 *
 * <p>The singleton is left unset until the first Channel becomes syncable, and readers treat an unset singleton as
 * no sequence number issued. Channels that became syncable before this schema are not indexed; CLPR is disabled by
 * default, so no network is expected to have any.
 */
public class V0800ClprSchema extends Schema<SemanticVersion> {

    /** Channel index singleton state ID */
    public static final int CHANNEL_INDEX_STATE_ID = SingletonType.CLPRSERVICE_I_CHANNEL_INDEX.protoOrdinal();

    /** Channel index singleton state key */
    public static final String CHANNEL_INDEX_KEY = "CHANNEL_INDEX";

    /** Channel index entries state ID */
    public static final int CHANNEL_INDEX_ENTRIES_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_CHANNEL_INDEX_ENTRIES.protoOrdinal();

    /** Channel index entries state key */
    public static final String CHANNEL_INDEX_ENTRIES_KEY = "CHANNEL_INDEX_ENTRIES";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(80).patch(0).build();

    /**
     * Constructor for this schema.
     */
    public V0800ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(CHANNEL_INDEX_STATE_ID, CHANNEL_INDEX_KEY, ClprChannelIndex.PROTOBUF),
                StateDefinition.keyValue(
                        CHANNEL_INDEX_ENTRIES_STATE_ID,
                        CHANNEL_INDEX_ENTRIES_KEY,
                        ProtoLong.PROTOBUF,
                        ProtoBytes.PROTOBUF));
    }
}
