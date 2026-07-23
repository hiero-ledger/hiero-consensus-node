// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.monitor;

import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.monitor.internal.DefaultPlatformMonitor;
import com.swirlds.platform.monitor.internal.PlatformMonitor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.PlatformStatusAction;

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
            @NonNull final NodeId selfId) {
        final StatusMonitorWiringConfig wiringConfig = configuration.getConfigData(StatusMonitorWiringConfig.class);
        platformMonitorWiring = new ComponentWiring<>(model, PlatformMonitor.class, wiringConfig.statusMonitor());

        final Duration heartbeatPeriod =
                configuration.getConfigData(PlatformStatusConfig.class).statusStateMachineHeartbeatPeriod();
        model.buildHeartbeatWire(heartbeatPeriod)
                .solderTo(platformMonitorWiring.getInputWire(PlatformMonitor::heartbeat), OFFER);

        // Create unbound wires
        platformMonitorWiring.getInputWire(PlatformMonitor::submitStatusAction);
        platformMonitorWiring.getInputWire(PlatformMonitor::quiescenceCommand);

        final PlatformMonitor platformMonitor = new DefaultPlatformMonitor(configuration, metrics, time, selfId);
        platformMonitorWiring.bind(platformMonitor);
    }

    /**
     * {@link InputWire} to inform the monitor that a state has been written to disk
     *
     * @return the {@link InputWire} for state saving monitoring
     */
    @NonNull
    @InputWireLabel("state saving monitoring")
    public InputWire<StateSavingResult> stateWrittenToDiskInputWire() {
        return platformMonitorWiring.getInputWire(PlatformMonitor::stateWrittenToDisk);
    }

    /**
     * {@link InputWire} to inform the monitor of ISS notifications
     *
     * @return the {@link InputWire} for ISS notification monitoring
     */
    @InputWireLabel("ISS notification monitoring")
    @NonNull
    public InputWire<IssNotification> issNotificationInputWire() {
        return platformMonitorWiring.getInputWire(PlatformMonitor::issNotification);
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
     * The primary output wire of the StatusMonitorModule. This output wire produces PlatformStatus notifications.
     *
     * @return the {@link OutputWire} for PlatformStatus notifications
     */
    @NonNull
    public OutputWire<PlatformStatus> platformStatusOutputWire() {
        return platformMonitorWiring.getOutputWire();
    }

    /**
     * Submit a status action to the platform monitor.
     *
     * @param action the status action to submit
     */
    public void submitStatusAction(@NonNull final PlatformStatusAction action) {
        platformMonitorWiring.getInputWire(PlatformMonitor::submitStatusAction).put(action);
    }

    /**
     * Submit a quiescence command to the platform monitor.
     *
     * @param quiescenceCommand the quiescence command to submit
     */
    public void submitQuiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        platformMonitorWiring.getInputWire(PlatformMonitor::quiescenceCommand).inject(quiescenceCommand);
    }

    /**
     * Flush the platform status state machine
     */
    public void flush() {
        platformMonitorWiring.flush();
    }
}
