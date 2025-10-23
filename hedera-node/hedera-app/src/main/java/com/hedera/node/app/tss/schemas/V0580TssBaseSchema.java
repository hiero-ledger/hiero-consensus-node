// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.tss.TssEncryptionKeys;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema for the TSS service.
 */
@Deprecated(forRemoval = true, since = "0.59.0")
public class V0580TssBaseSchema extends Schema<SemanticVersion> {

    public static final String TSS_ENCRYPTION_KEYS_KEY = "TSS_ENCRYPTION_KEYS";
    public static final int TSS_ENCRYPTION_KEYS_STATE_ID =
            StateKey.KeyOneOfType.TSSBASESERVICE_I_TSS_ENCRYPTION_KEYS.protoOrdinal();

    /**
     * This will at most be equal to the number of nodes in the network.
     */
    private static final long MAX_TSS_ENCRYPTION_KEYS = 65_536L;

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(58).patch(0).build();

    public V0580TssBaseSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                TSS_ENCRYPTION_KEYS_STATE_ID,
                TSS_ENCRYPTION_KEYS_KEY,
                EntityNumber.PROTOBUF,
                TssEncryptionKeys.PROTOBUF,
                MAX_TSS_ENCRYPTION_KEYS));
    }
}
