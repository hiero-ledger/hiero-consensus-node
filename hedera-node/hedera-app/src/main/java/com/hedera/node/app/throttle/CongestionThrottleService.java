// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.node.app.spi.schemas.SchemaRegistry;
import com.hedera.node.app.spi.services.Service;
import com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

@Singleton
public class CongestionThrottleService implements Service {
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
}
