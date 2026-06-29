// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Queue;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.management.config.StateManagementWiringConfig;
import org.hiero.consensus.state.management.hashing.DefaultHashLogger;
import org.hiero.consensus.state.management.hashing.DefaultStateHasher;
import org.hiero.consensus.state.management.hashing.HashLogger;
import org.hiero.consensus.state.management.hashing.StateHasher;
import org.hiero.consensus.state.management.signing.DefaultStateSignatureCollector;
import org.hiero.consensus.state.management.signing.DefaultStateSigner;
import org.hiero.consensus.state.management.signing.SignedStateMetrics;
import org.hiero.consensus.state.management.signing.StateSignatureCollector;
import org.hiero.consensus.state.management.signing.StateSigner;
import org.hiero.consensus.state.management.utils.SignedStateReserver;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;

/**
 * Module for signed state management.
 */
public class StateManagementModule {

    private final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring;
    private final ComponentWiring<HashLogger, Void> hashLoggerWiring;

    private final ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;

    private final OutputWire<ReservedSignedState> hashedStateOutputWire;
    private final OutputWire<ReservedSignedState> collectedSignedStatesOutputWire;

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
            @NonNull final Time time,
            @NonNull final KeysAndCerts keysAndCerts) {

        // Set up wiring
        final StateManagementWiringConfig wiringConfig = configuration.getConfigData(StateManagementWiringConfig.class);
        this.stateHasherWiring = new ComponentWiring<>(
                model,
                StateHasher.class,
                wiringConfig.stateHasher(),
                data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1);
        this.hashLoggerWiring = new ComponentWiring<>(model, HashLogger.class, wiringConfig.hashLogger());
        this.stateSignerWiring = new ComponentWiring<>(model, StateSigner.class, wiringConfig.stateSigner());
        this.stateSignatureCollectorWiring =
                new ComponentWiring<>(model, StateSignatureCollector.class, wiringConfig.stateSignatureCollector());

        // The state hasher needs to pass its data through a transformer.
        hashedStateOutputWire = stateHasherWiring
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        collectedSignedStatesOutputWire = stateSignatureCollectorWiring
                .getOutputWire()
                .<ReservedSignedState>buildSplitter("reservedStateSplitter", "reserved state lists")
                .buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));

        // Wire components
        hashedStateOutputWire.solderTo(hashedStatesToLogInputWire());
        hashedStateOutputWire.solderTo(stateSignerWiring.getInputWire(StateSigner::signState));
        hashedStateOutputWire.solderTo(stateToCollectInputWire());

        // Force not soldered wires to be built
        stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);

        // Create and bind components
        final StateHasher stateHasher = new DefaultStateHasher(metrics);
        stateHasherWiring.bind(stateHasher);
        final HashLogger hashLogger = new DefaultHashLogger(configuration);
        hashLoggerWiring.bind(hashLogger);
        final StateSigner stateSigner = new DefaultStateSigner(new PlatformSigner(keysAndCerts));
        stateSignerWiring.bind(stateSigner);
        final SignedStateMetrics signedStateMetrics = new SignedStateMetrics(metrics);
        final StateSignatureCollector stateSignatureCollector =
                new DefaultStateSignatureCollector(configuration, signedStateMetrics);
        stateSignatureCollectorWiring.bind(stateSignatureCollector);
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
     * Get the input wire for hashes states to log
     *
     * @return the input wire for hashes states to log
     */
    @InputWireLabel("hashed states")
    @NonNull
    public InputWire<ReservedSignedState> stateToCollectInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState);
    }

    /**
     * Get the input wire for {@link StateSignatureTransaction}s to handle preconensus.
     *
     * @return the input wire for the transactions
     */
    @InputWireLabel("preconsensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            preconsensusSystemTranscationsInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::handlePreconsensusSignatures);
    }

    /**
     * Get the input wire for {@link StateSignatureTransaction}s to handle post-conensus.
     *
     * @return the input wire for the transactions
     */
    @InputWireLabel("post consensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            postconsensusSystemTranscationsInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::handlePreconsensusSignatures);
    }

    /**
     * Get the output wire for hashed states
     *
     * @return the output wire for hashed states
     */
    @NonNull
    public OutputWire<ReservedSignedState> hashedStateOutputWire() {
        return hashedStateOutputWire;
    }

    /**
     * Get the output wire for state signature transactions
     *
     * @return the output wire for state signature transactions
     */
    @NonNull
    public OutputWire<StateSignatureTransaction> stateSignaturesOutputWire() {
        return stateSignerWiring.getOutputWire();
    }

    /**
     * Get the output wire for all collected states
     *
     * @return the output wire for hashed states
     */
    @NonNull
    public OutputWire<ReservedSignedState> collectedSignedStatesOutputWire() {
        return collectedSignedStatesOutputWire;
    }

    /**
     * Flush the preHandler.
     */
    public void flush() {
        stateHasherWiring.flush();
        stateSignatureCollectorWiring.flush();
    }

    /**
     * Get the input wire for clearing the state management component.
     * .
     * @return the input wire for clearing the state management component.
     */
    public InputWire<NoInput> clearInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);
    }
}
