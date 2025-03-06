// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.state.signed.SignedStateMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for building instances of {@link StateSignatureCollectorTester}.
 */
public class StateSignatureCollectorBuilder {

    private final PlatformContext platformContext;
    private final SignedStateMetrics metrics;
    private StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer = x -> {};
    private StateLacksSignaturesConsumer stateLacksSignaturesConsumer = x -> {};

    public StateSignatureCollectorBuilder(@NonNull final PlatformContext platformContext) {
        this.platformContext = platformContext;

        this.metrics = new SignedStateMetrics(platformContext.getMetrics());
    }

    public StateSignatureCollectorBuilder stateHasEnoughSignaturesConsumer(
            final StateHasEnoughSignaturesConsumer consumer) {
        this.stateHasEnoughSignaturesConsumer = consumer;
        return this;
    }

    public StateSignatureCollectorBuilder stateLacksSignaturesConsumer(final StateLacksSignaturesConsumer consumer) {
        this.stateLacksSignaturesConsumer = consumer;
        return this;
    }

    public StateSignatureCollectorTester build() {
        return StateSignatureCollectorTester.create(
                platformContext, metrics, stateHasEnoughSignaturesConsumer, stateLacksSignaturesConsumer);
    }
}
