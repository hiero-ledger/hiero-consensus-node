// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Predicate;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.event.stream.internal.ConsensusEventStream;
import org.hiero.consensus.event.stream.internal.DefaultConsensusEventStream;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.stream.RunningEventHashOverride;

public class EventStreamModule {

    private final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring;

    public EventStreamModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final String nodeName,
            @NonNull final Predicate<CesEvent> isLastEventInFreezeCheck) {
        // Set up wiring
        final EventStreamWiringConfig wiringConfig = configuration.getConfigData(EventStreamWiringConfig.class);
        this.consensusEventStreamWiring =
                new ComponentWiring<>(model, ConsensusEventStream.class, wiringConfig.consensusEventStream());

        // Create and bind components
        final Signer signer = (byte[] data) -> new PlatformSigner(keysAndCerts).sign(data);
        final ConsensusEventStream consensusEventStream = new DefaultConsensusEventStream(
                time, configuration, metrics, selfId, signer, nodeName, isLastEventInFreezeCheck);
        consensusEventStreamWiring.bind(consensusEventStream);
    }

    /**
     * Consensus events input wire.
     *
     * @return the input wire for consensus events
     */
    @InputWireLabel("consensus events")
    @NonNull
    public InputWire<List<CesEvent>> consensusEventsInputWire() {
        return consensusEventStreamWiring.getInputWire(ConsensusEventStream::addEvents);
    }

    /**
     * Hash override input wire.
     *
     * @return the input wire for hash override
     */
    @InputWireLabel("hash override")
    @NonNull
    public InputWire<RunningEventHashOverride> legacyHashOverrideInputWire() {
        return consensusEventStreamWiring.getInputWire(ConsensusEventStream::legacyHashOverride);
    }
}
