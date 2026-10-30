// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Queue;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.io.SerializableLong;
import org.hiero.consensus.iss.detection.config.IssDetectionWiringConfig;
import org.hiero.consensus.iss.detection.internal.DefaultIssDetector;
import org.hiero.consensus.iss.detection.internal.DefaultIssHandler;
import org.hiero.consensus.iss.detection.internal.IssDetector;
import org.hiero.consensus.iss.detection.internal.IssHandler;
import org.hiero.consensus.iss.detection.internal.IssScratchpad;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.pces.config.PcesConfig;
import org.hiero.consensus.scratchpad.Scratchpad;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.wiring.framework.component.ComponentWiring;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

/**
 * Module for the Iss Detection component.
 */
public class IssDetectionModule {

    @NonNull
    private final ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring;

    @NonNull
    private final InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> systemTransactionsInputWire;

    @NonNull
    private final InputWire<ReservedSignedState> stateInputWire;

    @NonNull
    private final InputWire<ReservedSignedState> overridingStateInputWire;

    @NonNull
    private final InputWire<NoInput> signalEndOfPreconsensusReplayInputWire;

    /**
     * Constructor of {@code IssDetectionModule}
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time system
     * @param roster the roster
     * @param selfId the node ID of this node
     * @param fileSystemManager the file system manager
     * @param initialStateRound the round of the initial state
     * @param latestFreezeRound the round of the latest freeze
     * @param fatalErrorConsumer the consumer for fatal errors
     */
    public IssDetectionModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FileSystemManager fileSystemManager,
            final long initialStateRound,
            final long latestFreezeRound,
            @NonNull final FatalErrorConsumer fatalErrorConsumer) {

        // Set up wired components
        final IssDetectionWiringConfig wiringConfig = configuration.getConfigData(IssDetectionWiringConfig.class);
        this.issDetectorWiring = new ComponentWiring<>(model, IssDetector.class, wiringConfig.issDetector());
        final ComponentWiring<IssHandler, Void> issHandlerWiring =
                new ComponentWiring<>(model, IssHandler.class, wiringConfig.issHandler());

        // Set up input wires
        systemTransactionsInputWire = issDetectorWiring.getInputWire(IssDetector::handleStateSignatureTransactions);
        stateInputWire = issDetectorWiring.getInputWire(IssDetector::handleState);
        overridingStateInputWire = issDetectorWiring.getInputWire(IssDetector::overridingState);
        signalEndOfPreconsensusReplayInputWire =
                issDetectorWiring.getInputWire(IssDetector::signalEndOfPreconsensusReplay);

        // Wire components
        issDetectorWiring
                .<IssNotification>getSplitOutput()
                .solderTo(issHandlerWiring.getInputWire(IssHandler::issObserved));

        // Create and bind components
        final Scratchpad<IssScratchpad> issScratchpad =
                Scratchpad.create(fileSystemManager, selfId, IssScratchpad.class, "platform.iss");
        issScratchpad.logContents();

        final SerializableLong issRound = issScratchpad.get(IssScratchpad.LAST_ISS_ROUND);

        final boolean forceIgnorePcesSignatures =
                configuration.getConfigData(PcesConfig.class).forceIgnorePcesSignatures();

        // Only validate preconsensus signature transactions if we are not recovering from an ISS.
        // ISS round == null means we haven't observed an ISS yet.
        // ISS round < current round means there was an ISS prior to the saved state
        //    that has already been recovered from.
        // ISS round >= current round means that the ISS happens in the future relative the initial state, meaning
        //    we may observe ISS-inducing signature transactions in the preconsensus event stream.
        final boolean ignorePreconsensusSignatures;
        if (forceIgnorePcesSignatures) {
            // this is used FOR TESTING ONLY
            ignorePreconsensusSignatures = true;
        } else {
            ignorePreconsensusSignatures = issRound != null && issRound.getValue() >= initialStateRound;
        }

        final IssDetector issDetector = new DefaultIssDetector(
                time, configuration, metrics, roster, ignorePreconsensusSignatures, latestFreezeRound);
        issDetectorWiring.bind(issDetector);
        final IssHandler issHandler = new DefaultIssHandler(configuration, fatalErrorConsumer, issScratchpad);
        issHandlerWiring.bind(issHandler);
    }

    /**
     * Get the input wire for post-consensus state signature transactions.
     *
     * @return the input wire for post-consensus state signature transactions
     */
    @InputWireLabel("post consensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> systemTransactionsInputWire() {
        return systemTransactionsInputWire;
    }

    /**
     * Get the input wire for hashed states.
     *
     * @return the input wire for hashed states
     */
    @InputWireLabel("hashed states")
    @NonNull
    public InputWire<ReservedSignedState> stateInputWire() {
        return stateInputWire;
    }

    /**
     * Get the input wire for overriding state.
     *
     * @return the input wire for overriding state
     */
    @InputWireLabel("overriding state")
    @NonNull
    public InputWire<ReservedSignedState> overridingStateInputWire() {
        return overridingStateInputWire;
    }

    @InputWireLabel("signal end of preconsensus replay")
    @NonNull
    public InputWire<NoInput> signalEndOfPreconsensusReplayInputWire() {
        return signalEndOfPreconsensusReplayInputWire;
    }

    /**
     * Get the output wire for ISS notifications.
     *
     * @return the output wire for ISS notifications
     */
    @NonNull
    public OutputWire<IssNotification> issNotificationOutputWire() {
        return issDetectorWiring.getSplitOutput();
    }
}
