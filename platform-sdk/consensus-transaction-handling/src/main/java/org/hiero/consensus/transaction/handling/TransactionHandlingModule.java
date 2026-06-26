// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;
import org.hiero.consensus.status.StatusActionSubmitter;
import org.hiero.consensus.transaction.handling.config.TransactionHandlingWiringConfig;
import org.hiero.consensus.transaction.handling.internal.DefaultTransactionHandler;
import org.hiero.consensus.transaction.handling.internal.DefaultTransactionPrehandler;
import org.hiero.consensus.transaction.handling.internal.StateWithHashComplexityReserver;
import org.hiero.consensus.transaction.handling.internal.StateWithHashComplexityToStateReserver;
import org.hiero.consensus.transaction.handling.internal.TransactionHandler;
import org.hiero.consensus.transaction.handling.internal.TransactionHandlerDataCounter;
import org.hiero.consensus.transaction.handling.internal.TransactionHandlerResult;
import org.hiero.consensus.transaction.handling.internal.TransactionPrehandler;

public class TransactionHandlingModule {

    public static final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> NO_OP_CONSUMER =
            systemTransactions -> {};

    private final ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            prehanderWiring;
    private final ComponentWiring<TransactionHandler, TransactionHandlerResult> handlerWiring;

    private final OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> handleSignaturesOutputWire;
    private final OutputWire<ReservedSignedState> stateOutputWire;
    private final OutputWire<StateWithHashComplexity> stateWithHashComplexityOutputWire;

    /**
     * Constructor for {@code TransactionHandlingModule}
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time source
     * @param latestImmutableStateProviderReference the latest immutable state provider reference
     * @param transactionCallbacks the transaction callbacks
     * @param stateLifecycleManager the state lifecycle manager
     * @param statusActionSubmitterReference the status action submitter reference
     * @param softwareVersion the software version
     * @param selfId the node id
     * @param transactionOffsetNanos the transaction offset in nanoseconds
     */
    public TransactionHandlingModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final AtomicReference<Function<String, ReservedSignedState>> latestImmutableStateProviderReference,
            @NonNull final TransactionCallbacks transactionCallbacks,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final AtomicReference<StatusActionSubmitter> statusActionSubmitterReference,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final NodeId selfId,
            final long transactionOffsetNanos) {

        // Set up wiring
        final TransactionHandlingWiringConfig wiringConfig =
                configuration.getConfigData(TransactionHandlingWiringConfig.class);
        this.prehanderWiring = new ComponentWiring<>(model, TransactionPrehandler.class, wiringConfig.prehandler());
        this.handlerWiring = new ComponentWiring<>(
                model,
                TransactionHandler.class,
                wiringConfig.handler(),
                TransactionHandlerDataCounter.create(wiringConfig.handler()));

        // The TransactionHandler output is split into two types: system transactions, and state with complexity.
        this.handleSignaturesOutputWire = handlerWiring
                .getOutputWire()
                .buildTransformer(
                        "getSystemTransactions",
                        "transaction handler result",
                        TransactionHandlerResult::systemTransactions);
        this.stateWithHashComplexityOutputWire = handlerWiring
                .getOutputWire()
                .buildFilter(
                        "notNullStateFilter",
                        "transaction handler result",
                        thr -> thr.stateWithHashComplexity() != null)
                .buildAdvancedTransformer(
                        new StateWithHashComplexityReserver("postHandler_stateWithHashComplexityReserver"));
        this.stateOutputWire = stateWithHashComplexityOutputWire.buildAdvancedTransformer(
                new StateWithHashComplexityToStateReserver("postHandler_stateWithHashComplexityToStateReserver"));

        // Create and bind components
        final TransactionPrehandler transactionPrehandler = new DefaultTransactionPrehandler(
                metrics,
                time,
                () -> latestImmutableStateProviderReference.get().apply("transaction prehandle"),
                transactionCallbacks);
        prehanderWiring.bind(transactionPrehandler);
        final TransactionHandler transactionHandler = new DefaultTransactionHandler(
                time,
                configuration,
                metrics,
                stateLifecycleManager,
                statusActionSubmitterReference,
                softwareVersion,
                transactionCallbacks,
                selfId,
                transactionOffsetNanos);
        handlerWiring.bind(transactionHandler);
    }

    /**
     * Get the input wire for events for preHandle
     *
     * @return the input wire for events for preHandle
     */
    @InputWireLabel("preconsensus event")
    @NonNull
    public InputWire<PlatformEvent> preHandleEventInputWire() {
        return prehanderWiring.getInputWire(TransactionPrehandler::prehandleApplicationTransactions);
    }

    /**
     * Get the input wire for events for handle
     *
     * @return the input wire for events for preHandle
     */
    @InputWireLabel("consensus round")
    @NonNull
    public InputWire<ConsensusRound> handleConsensusRoundInputWire() {
        return handlerWiring.getInputWire(TransactionHandler::handleConsensusRound);
    }

    /**
     * Get the input wire for hash override
     *
     * @return the input wire for hash override
     */
    @InputWireLabel("hash override")
    @NonNull
    public InputWire<RunningEventHashOverride> hashOverrideInputWire() {
        return handlerWiring.getInputWire(TransactionHandler::updateLegacyRunningEventHash);
    }

    /**
     * Get the output wire for state
     *
     * @return the output wire for state
     */
    @NonNull
    public OutputWire<ReservedSignedState> stateOutputWire() {
        return stateOutputWire;
    }

    /**
     * Get the output wire for state with hash complexity
     *
     * @return the output wire for state with hash complexity
     */
    @NonNull
    public OutputWire<StateWithHashComplexity> stateWithHashComplexityOutputWire() {
        return stateWithHashComplexityOutputWire;
    }

    /**
     * Get the output wire for preHandle state signature transactions
     *
     * @return the output wire for preHandle state signature transactions
     */
    @NonNull
    public OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> preHandleSignaturesOutputWire() {
        return prehanderWiring.getOutputWire();
    }

    /**
     * Get the output wire for handle state signature transactions
     *
     * @return the output wire for handle state signature transactions
     */
    @NonNull
    public OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> handleSignaturesOutputWire() {
        return handleSignaturesOutputWire;
    }

    /**
     * Flush the preHandler.
     */
    public void flushTransactionPreHandler() {
        prehanderWiring.flush();
    }

    /**
     * Flush the handler.
     */
    public void flushTransactionHandler() {
        handlerWiring.flush();
    }

    /**
     * Start squelching the transaction handler.
     */
    public void startSquelchingTransactionHandler() {
        handlerWiring.startSquelching();
    }

    /**
     * Stop squelching the transaction handler.
     */
    public void stopSquelchingTransactionHandler() {
        handlerWiring.stopSquelching();
    }
}
