// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.SingletonType;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0490CongestionThrottleSchema extends Schema<SemanticVersion> {

    private static final Logger log = LogManager.getLogger(V0490CongestionThrottleSchema.class);

    public static final String THROTTLE_USAGE_SNAPSHOTS_KEY = "THROTTLE_USAGE_SNAPSHOTS";
    public static final int THROTTLE_USAGE_SNAPSHOTS_STATE_ID =
            SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal();

    public static final String CONGESTION_LEVEL_STARTS_KEY = "CONGESTION_LEVEL_STARTS";
    public static final int CONGESTION_LEVEL_STARTS_STATE_ID =
            SingletonType.CONGESTIONTHROTTLESERVICE_I_CONGESTION_LEVEL_STARTS.protoOrdinal();

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public V0490CongestionThrottleSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(
                        THROTTLE_USAGE_SNAPSHOTS_STATE_ID,
                        THROTTLE_USAGE_SNAPSHOTS_KEY,
                        ThrottleUsageSnapshots.PROTOBUF),
                StateDefinition.singleton(
                        CONGESTION_LEVEL_STARTS_STATE_ID, CONGESTION_LEVEL_STARTS_KEY, CongestionLevelStarts.PROTOBUF));
    }

    /** {@inheritDoc} */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (ctx.previousVersion() == null) {
            log.info("Creating genesis throttle snapshots and congestion level starts");
            // At genesis we put empty throttle usage snapshots and
            // congestion level starts into their respective singleton
            // states just to ensure they exist
            final var throttleSnapshots = ctx.newStates().getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID);
            throttleSnapshots.put(ThrottleUsageSnapshots.DEFAULT);
            final var congestionLevelStarts = ctx.newStates().getSingleton(CONGESTION_LEVEL_STARTS_STATE_ID);
            congestionLevelStarts.put(CongestionLevelStarts.DEFAULT);
        }
    }
}
