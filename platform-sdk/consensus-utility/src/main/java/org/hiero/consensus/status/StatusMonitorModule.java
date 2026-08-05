// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import static com.swirlds.component.framework.wires.SolderType.OFFER;

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
import java.time.Duration;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.PlatformStatusAction;
import org.hiero.consensus.status.internal.DefaultPlatformMonitor;
import org.hiero.consensus.status.internal.PlatformMonitor;

/**
 * The StatusMonitorModule is responsible for monitoring the platform's status and updating the platform's
 * status state machine. It provides input wires for various events and actions that can affect the platform's status,
 * and an output wire for producing PlatformStatus notifications.
 */
public class StatusMonitorModule {

    final ComponentWiring<PlatformMonitor, PlatformStatus> platformMonitorWiring;

    /**
     * Create a new StatusMonitorModule.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time
     * @param selfId the node ID of this node
     */
    public StatusMonitorModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezePeriodChecker) {
        final StatusMonitorWiringConfig wiringConfig = configuration.getConfigData(StatusMonitorWiringConfig.class);
        platformMonitorWiring = new ComponentWiring<>(model, PlatformMonitor.class, wiringConfig.statusMonitor());

        final Duration heartbeatPeriod =
                configuration.getConfigData(PlatformStatusConfig.class).statusStateMachineHeartbeatPeriod();
        model.buildHeartbeatWire(heartbeatPeriod)
                .solderTo(platformMonitorWiring.getInputWire(PlatformMonitor::heartbeat), OFFER);

        // Create unbound wires
        platformMonitorWiring.getInputWire(PlatformMonitor::submitStatusAction);
        platformMonitorWiring.getInputWire(PlatformMonitor::quiescenceCommand);

        final PlatformMonitor platformMonitor = new DefaultPlatformMonitor(configuration, metrics, time, selfId, freezePeriodChecker);
        platformMonitorWiring.bind(platformMonitor);
    }

    /**
     * {@link InputWire} to inform the monitor about a consensus rounds
     *
     * @return the {@link InputWire} for monitoring consensus rounds
     */
    @InputWireLabel("monitor consensus round")
    @NonNull
    public InputWire<ConsensusRound> consensusRoundInputWire() {
        return platformMonitorWiring.getInputWire(PlatformMonitor::consensusRound);
    }

    /**
     * {@link InputWire} to submit a PlatformStatusAction to the monitor
     *
     * @return the {@link InputWire} for submitting PlatformStatusAction
     */
    @InputWireLabel("platform status action")
    @NonNull
    public InputWire<PlatformStatusAction> platformStatusActionInputWire() {
        return platformMonitorWiring.getInputWire(PlatformMonitor::submitStatusAction);
    }

    /**
     * {@link InputWire} to submit a quiescence command to the platform monitor.
     *
     * @return the {@link InputWire} for submitting quiescence commands
     */
    @NonNull
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return platformMonitorWiring.getInputWire(PlatformMonitor::quiescenceCommand);
    }

    /**
     * The primary output wire of the StatusMonitorModule. This output wire produces PlatformStatus notifications.
     *
     * @return the {@link OutputWire} for PlatformStatus notifications
     */
    @NonNull
    public OutputWire<PlatformStatus> platformStatusOutputWire() {
        return platformMonitorWiring.getOutputWire();
    }

    /**
     * Flush the platform status state machine
     */
    public void flush() {
        platformMonitorWiring.flush();
    }
}
