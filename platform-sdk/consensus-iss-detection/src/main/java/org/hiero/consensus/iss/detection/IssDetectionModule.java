// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.iss.detection.internal.IssDetector.DO_NOT_IGNORE_ROUNDS;

import com.hedera.hapi.node.state.roster.Roster;
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
import edu.umd.cs.findbugs.annotations.Nullable;
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
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Module for the Iss Detection component.
 */
public class IssDetectionModule {

    @Nullable
    private final ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring;

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
        // Only validate preconsensus signature transactions if we are not recovering from an ISS.
        // ISS round == null means we haven't observed an ISS yet.
        // ISS round < current round means there was an ISS prior to the saved state
        //    that has already been recovered from.
        // ISS round >= current round means that the ISS happens in the future relative the initial state, meaning
        //    we may observe ISS-inducing signature transactions in the preconsensus event stream.

        // Set up wiring
        final IssDetectionWiringConfig wiringConfig = configuration.getConfigData(IssDetectionWiringConfig.class);
        this.issDetectorWiring = new ComponentWiring<>(model, IssDetector.class, wiringConfig.issDetector());
        final ComponentWiring<IssHandler, Void> issHandlerWiring =
                new ComponentWiring<>(model, IssHandler.class, wiringConfig.issHandler());

        // Wire components
        issDetectorWiring
                .<IssNotification>getSplitOutput()
                .solderTo(issHandlerWiring.getInputWire(IssHandler::issObserved));

        // Force not soldered wires to be built
        issDetectorWiring.getInputWire(IssDetector::overridingState);
        issDetectorWiring.getInputWire(IssDetector::signalEndOfPreconsensusReplay);

        // Create and bind components
        final Scratchpad<IssScratchpad> issScratchpad =
                Scratchpad.create(fileSystemManager, selfId, IssScratchpad.class, "platform.iss");
        issScratchpad.logContents();

        final SerializableLong issRound = issScratchpad.get(IssScratchpad.LAST_ISS_ROUND);

        final boolean forceIgnorePcesSignatures =
                configuration.getConfigData(PcesConfig.class).forceIgnorePcesSignatures();

        final boolean ignorePreconsensusSignatures;
        if (forceIgnorePcesSignatures) {
            // this is used FOR TESTING ONLY
            ignorePreconsensusSignatures = true;
        } else {
            ignorePreconsensusSignatures = issRound != null && issRound.getValue() >= initialStateRound;
        }

        // A round that we will completely skip ISS detection for. Needed for tests that do janky state modification
        // without a software upgrade (in production this feature should not be used).
        final long roundToIgnore =
                configuration.getConfigData(StateConfig.class).validateInitialState()
                        ? DO_NOT_IGNORE_ROUNDS
                        : initialStateRound;

        final IssDetector issDetector = new DefaultIssDetector(
                time, configuration, metrics, roster, ignorePreconsensusSignatures, roundToIgnore, latestFreezeRound);
        issDetectorWiring.bind(issDetector);
        final IssHandler issHandler = new DefaultIssHandler(configuration, fatalErrorConsumer, issScratchpad);
        issHandlerWiring.bind(issHandler);
    }

    @InputWireLabel("post consensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> systemTransactionsInputWire() {
        return requireNonNull(issDetectorWiring, "Not initialized")
                .getInputWire(IssDetector::handleStateSignatureTransactions);
    }

    @InputWireLabel("hashed states")
    @NonNull
    public InputWire<ReservedSignedState> stateInputWire() {
        return requireNonNull(issDetectorWiring, "Not initialized").getInputWire(IssDetector::handleState);
    }

    @InputWireLabel("overriding state")
    @NonNull
    public InputWire<ReservedSignedState> overridingStateInputWire() {
        return requireNonNull(issDetectorWiring, "Not initialized").getInputWire(IssDetector::handleState);
    }

    @NonNull
    public InputWire<NoInput> signalEndOfPreconsensusReplayInputWire() {
        return requireNonNull(issDetectorWiring, "Not initialized")
                .getInputWire(IssDetector::signalEndOfPreconsensusReplay);
    }

    @NonNull
    public OutputWire<IssNotification> issNotificationOutputWire() {
        return requireNonNull(issDetectorWiring, "Not initialized").getSplitOutput();
    }
}
