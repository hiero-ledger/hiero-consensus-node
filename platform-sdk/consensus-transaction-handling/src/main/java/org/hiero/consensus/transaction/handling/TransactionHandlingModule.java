package org.hiero.consensus.transaction.handling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.transaction.handling.config.TransactionHandlingWiringConfig;
import org.hiero.consensus.transaction.handling.internal.DefaultTransactionPrehandler;
import org.hiero.consensus.transaction.handling.internal.TransactionPrehandler;

public class TransactionHandlingModule {

    public static final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> NO_OP_CONSUMER =
            systemTransactions -> {};

    private final ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>> prehanderWiring;

    /**
     * Constructor for {@code TransactionHandlingModule}
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time source
     * @param latestImmutableStateProviderReference the latest immutable state provider reference
     * @param preHandleCallback the state lifecycles callback to be called during preHandle
     */
    public TransactionHandlingModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final AtomicReference<Function<String, ReservedSignedState>> latestImmutableStateProviderReference,
            @NonNull final PreHandleCallback preHandleCallback) {

        // Set up wiring
        final TransactionHandlingWiringConfig wiringConfig = configuration.getConfigData(
                TransactionHandlingWiringConfig.class);
        this.prehanderWiring = new ComponentWiring<>(model, TransactionPrehandler.class, wiringConfig.prehandler());

        // Wire components
//        issDetectorWiring
//                .<IssNotification>getSplitOutput()
//                .solderTo(issHandlerWiring.getInputWire(IssHandler::issObserved));

        // Force not soldered wires to be built
//        issDetectorWiring.getInputWire(IssDetector::overridingState);
//        issDetectorWiring.getInputWire(IssDetector::signalEndOfPreconsensusReplay);

        // Create and bind components
        final TransactionPrehandler transactionPrehandler = new DefaultTransactionPrehandler(
                metrics,
                time,
                () -> latestImmutableStateProviderReference.get().apply("transaction prehandle"),
                preHandleCallback
        );
        prehanderWiring.bind(transactionPrehandler);
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
     * Get the output wire for preHandle state signature transactions
     *
     * @return the output wire for preHandle state signature transactions
     */
    @NonNull
    public OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> preHandleSignaturesOutputWire() {
        return prehanderWiring.getOutputWire();
    }

    /**
     * Flush the module.
     */
    public void flush() {
        prehanderWiring.flush();
    }
}
