// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.instrumented.test.fixtures;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.impl.DefaultEventCreatorModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.consensus.test.fixtures.eventbus.EventBus;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

/**
 * An {@link EventCreatorModule} that wraps the {@link DefaultEventCreatorModule} and receives commands from tests via
 * the {@link EventBus} of its node.
 *
 * <p>Callbacks registered on the {@link EventBus} run on the thread of the publisher, not on a scheduler of the
 * wiring framework.
 */
public class InstrumentedEventCreatorModule implements EventCreatorModule {

    private static final Logger log = LogManager.getLogger();

    /** The channel on which ping messages are received. */
    public static final String PING_CHANNEL = "ping";

    /** Regular implementation that provides the standard behavior. */
    private final DefaultEventCreatorModule delegate = new DefaultEventCreatorModule();

    /** The callback registered for ping messages. Kept as a field, so the same instance can be unsubscribed. */
    private final Consumer<String> pingCallback = this::onPing;

    /** The event bus of this node, set during initialization. */
    @Nullable
    private EventBus eventBus;

    /**
     * Constructs an instance of this class.
     */
    public InstrumentedEventCreatorModule() {
        log.info(STARTUP.getMarker(), "InstrumentedEventCreatorModule created");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SecureRandom random,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final RosterWrapper roster,
            @NonNull final NodeId selfId,
            @NonNull final EventTransactionSupplier transactionSupplier,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck) {
        delegate.initialize(
                model,
                configuration,
                metrics,
                time,
                random,
                keysAndCerts,
                roster,
                selfId,
                transactionSupplier,
                signatureTransactionCheck);

        eventBus = EventBus.getInstance(selfId);
        eventBus.subscribe(PING_CHANNEL, pingCallback);
    }

    private void onPing(@NonNull final String message) {
        log.info(DEMO_INFO.getMarker(), "Ping message received: {}", message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> createdEventOutputWire() {
        return delegate.createdEventOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> orderedEventInputWire() {
        return delegate.orderedEventInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<ConsensusRound> consensusRoundInputWire() {
        return delegate.consensusRoundInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> initialEventWindowInputWire() {
        return delegate.initialEventWindowInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return delegate.platformStatusInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> healthStatusInputWire() {
        return delegate.healthStatusInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<SyncProgress> syncProgressInputWire() {
        return delegate.syncProgressInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return delegate.quiescenceCommandInputWire();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also shuts down the {@link EventBus} of this node, so that a restarted node does not keep the callbacks of its
     * previous instance.
     */
    @Override
    public void destroy() {
        if (eventBus != null) {
            eventBus.shutdown();
            eventBus = null;
        }
        delegate.destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        delegate.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {
        delegate.startSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {
        delegate.stopSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Object> clearCreationManagerInputWire() {
        return delegate.clearCreationManagerInputWire();
    }
}
