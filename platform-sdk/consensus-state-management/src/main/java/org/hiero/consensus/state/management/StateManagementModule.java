// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.state.management.config.StateManagementWiringConfig;
import org.hiero.consensus.state.management.hashing.DefaultHashLogger;
import org.hiero.consensus.state.management.hashing.DefaultStateHasher;
import org.hiero.consensus.state.management.hashing.HashLogger;
import org.hiero.consensus.state.management.hashing.StateHasher;
import org.hiero.consensus.state.management.utils.SignedStateReserver;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;

/**
 * Module for signed state management.
 */
public class StateManagementModule {

    private final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring;
    private final ComponentWiring<HashLogger, Void> hashLoggerWiring;

    private final OutputWire<ReservedSignedState> hashedStateOutputWire;

    /**
     * Constructor for {@code TransactionHandlingModule}
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time source
     */
    public StateManagementModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {

        // Set up wiring
        final StateManagementWiringConfig wiringConfig = configuration.getConfigData(StateManagementWiringConfig.class);
        this.stateHasherWiring = new ComponentWiring<>(
                model,
                StateHasher.class,
                wiringConfig.stateHasher(),
                data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1);
        this.hashLoggerWiring = new ComponentWiring<>(model, HashLogger.class, wiringConfig.hashLogger());

        // The state hasher needs to pass its data through a transformer.
        hashedStateOutputWire = stateHasherWiring
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));

        // Wire components
        hashedStateOutputWire.solderTo(hashedStatesToLogInputWire());

        // Force not soldered wires to be built

        // Create and bind components
        final StateHasher stateHasher = new DefaultStateHasher(metrics);
        stateHasherWiring.bind(stateHasher);
        final HashLogger hashLogger = new DefaultHashLogger(configuration);
        hashLoggerWiring.bind(hashLogger);
    }

    /**
     * Get the input wire for unhashed states
     *
     * @return the input wire for hashes states
     */
    @InputWireLabel("unhashed state with hash complexity")
    @NonNull
    public InputWire<StateWithHashComplexity> unhashedStatesInputWire() {
        return stateHasherWiring.getInputWire(StateHasher::hashState);
    }

    /**
     * Get the input wire for hashes states to log
     *
     * @return the input wire for hashes states to log
     */
    @InputWireLabel("hashed states to log")
    @NonNull
    public InputWire<ReservedSignedState> hashedStatesToLogInputWire() {
        return hashLoggerWiring.getInputWire(HashLogger::logHashes);
    }

    /**
     * Get the output wire for hashed states
     *
     * @return the output wire for state
     */
    @NonNull
    public OutputWire<ReservedSignedState> stateOutputWire() {
        return hashedStateOutputWire;
    }

    /**
     * Flush the preHandler.
     */
    public void flush() {
        stateHasherWiring.flush();
    }
}
