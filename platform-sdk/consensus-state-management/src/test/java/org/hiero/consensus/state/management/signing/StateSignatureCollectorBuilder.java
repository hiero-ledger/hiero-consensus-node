// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management.signing;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for building instances of {@link StateSignatureCollectorTester}.
 */
public class StateSignatureCollectorBuilder {

    private final Configuration configuration;
    private StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer = x -> {};
    private StateLacksSignaturesConsumer stateLacksSignaturesConsumer = x -> {};

    public StateSignatureCollectorBuilder(@NonNull final Configuration configuration) {
        this.configuration = configuration;
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
                configuration, stateHasEnoughSignaturesConsumer, stateLacksSignaturesConsumer);
    }
}
