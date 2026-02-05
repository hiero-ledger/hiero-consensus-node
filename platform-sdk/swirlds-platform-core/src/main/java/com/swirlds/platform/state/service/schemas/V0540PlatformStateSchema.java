// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the {@link PlatformState} singleton and initializes it at genesis.
 */
public class V0540PlatformStateSchema extends Schema<SemanticVersion> {
    public static final String PLATFORM_STATE_KEY = "PLATFORM_STATE";
    // FUTURE WORK: get rid of this dependency on SingletonType
    public static final int PLATFORM_STATE_STATE_ID =
            SingletonType.PLATFORMSTATESERVICE_I_PLATFORM_STATE.protoOrdinal();
    public static final String PLATFORM_STATE_STATE_LABEL = computeLabel(PlatformStateService.NAME, PLATFORM_STATE_KEY);

    /**
     * A platform state to be used as the non-null platform state under any circumstance a genesis state
     * is encountered before initializing the States API.
     */
    public static final PlatformState UNINITIALIZED_PLATFORM_STATE =
            new PlatformState(null, 0, ConsensusSnapshot.DEFAULT, null, null, 0L, Bytes.EMPTY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(54).patch(0).build();

    public V0540PlatformStateSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(PLATFORM_STATE_STATE_ID, PLATFORM_STATE_KEY, PlatformState.PROTOBUF));
    }
}
