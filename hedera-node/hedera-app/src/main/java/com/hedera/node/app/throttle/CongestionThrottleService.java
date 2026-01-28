// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.CONGESTION_LEVEL_STARTS_STATE_ID;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.THROTTLE_USAGE_SNAPSHOTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class CongestionThrottleService implements Service {
    private static final Logger log = LogManager.getLogger(CongestionThrottleService.class);

    public static final String NAME = "CongestionThrottleService";

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490CongestionThrottleSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates
                .<ThrottleUsageSnapshots>getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID)
                .put(ThrottleUsageSnapshots.DEFAULT);
        writableStates
                .<CongestionLevelStarts>getSingleton(CONGESTION_LEVEL_STARTS_STATE_ID)
                .put(CongestionLevelStarts.DEFAULT);
        return true;
    }
}
