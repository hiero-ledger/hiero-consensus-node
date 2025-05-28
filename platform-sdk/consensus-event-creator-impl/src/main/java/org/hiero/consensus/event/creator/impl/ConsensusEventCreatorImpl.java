// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import static com.swirlds.component.framework.wires.SolderType.OFFER;
import static org.hiero.consensus.model.event.StaleEventDetectorOutput.SELF_EVENT;
import static org.hiero.consensus.model.event.StaleEventDetectorOutput.STALE_SELF_EVENT;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.ConsensusEventCreator;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;
import org.hiero.consensus.event.creator.impl.config.WiringConfig;
import org.hiero.consensus.event.creator.impl.pool.TransactionPool;
import org.hiero.consensus.event.creator.impl.pool.TransactionPoolNexus;
import org.hiero.consensus.event.creator.impl.stale.StaleEventDetector;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Default implementation of the {@link ConsensusEventCreator}.
 */
public class ConsensusEventCreatorImpl implements ConsensusEventCreator {

    private ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;
    private ComponentWiring<StaleEventDetector, List<RoutableData<StaleEventDetectorOutput>>>
            staleEventDetectorWiring;
    private ComponentWiring<TransactionPool, Void> transactionPoolWiring;

    private WireTransformer<Duration, Duration> healthStatusDistributor;
    private WireTransformer<PlatformStatus, PlatformStatus> platformStatusDistributor;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> getOrderedEventsInputWire() {
        return eventCreationManagerWiring.getInputWire(EventCreationManager::registerEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<ConsensusRound> getRoundsInputWire() {
        return staleEventDetectorWiring.getInputWire(StaleEventDetector::addConsensusRound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> getNewSelfEventOutputWire() {
        return staleEventDetectorWiring.getSplitAndRoutedOutput(SELF_EVENT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> getStaleEventOutputWire() {
        return staleEventDetectorWiring.getSplitAndRoutedOutput(STALE_SELF_EVENT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusEventCreator registerTransactionRequestListener(
            @NonNull final TransactionRequestListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusEventCreator unregisterTransactionRequestListener(
            @NonNull final TransactionRequestListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Duration> getHealthStatusInputWire() {
        return healthStatusDistributor.getInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformStatus> getPlatformStatusInputWire() {
        return platformStatusDistributor.getInputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusEventCreator initialize(
            @NonNull final Configuration configuration,
            @NonNull final WiringModel model,
            @NonNull final Time time,
            @NonNull final Metrics metrics) {
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);
        final EventCreationConfig eventCreationConfig = configuration.getConfigData(EventCreationConfig.class);

        eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, wiringConfig.eventCreationManager());
        staleEventDetectorWiring = new ComponentWiring<>(model, StaleEventDetector.class, wiringConfig.staleEventDetector());
        transactionPoolWiring = new ComponentWiring<>(model, TransactionPool.class, wiringConfig.transactionPool());

        healthStatusDistributor = new WireTransformer<>(model, "HealthStatusDistributor",
                "healthStatusDistributorInput", Function.identity());
        healthStatusDistributor.getOutputWire().solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::reportUnhealthyDuration));
        healthStatusDistributor.getOutputWire().solderTo(transactionPoolWiring.getInputWire(TransactionPool::reportUnhealthyDuration));

        platformStatusDistributor = new WireTransformer<>(model, "PlatformStatusDistributor",
                "platformStatusDistributorInput", Function.identity());
        platformStatusDistributor.getOutputWire().solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::updatePlatformStatus));
        platformStatusDistributor.getOutputWire().solderTo(transactionPoolWiring.getInputWire(TransactionPool::updatePlatformStatus));

        final double eventCreationHeartbeatFrequency =eventCreationConfig.creationAttemptRate();
        model.buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent), OFFER);

        eventCreationManagerWiring
                .getOutputWire()
                .solderTo(staleEventDetectorWiring.getInputWire(StaleEventDetector::addSelfEvent));

        /*
         * {@link ComponentWiring} objects build their input wires when you first request them. Normally that happens when
         * we are soldering things together, but there are a few wires that aren't soldered and aren't used until later in
         * the lifecycle. This method forces those wires to be built.
         */
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear);
        staleEventDetectorWiring.getInputWire(StaleEventDetector::setInitialEventWindow);
        staleEventDetectorWiring.getInputWire(StaleEventDetector::clear);
        transactionPoolWiring.getInputWire(TransactionPool::clear);

        final TransactionPoolNexus transactionPoolNexus = new TransactionPoolNexus(configuration, time, metrics);
        final EventCreator eventCreator = new TipsetEventCreator(
                blocks.platformContext(),
                blocks.randomBuilder().buildNonCryptographicRandom(),
                data -> new PlatformSigner(blocks.keysAndCerts()).sign(data),
                blocks.rosterHistory().getCurrentRoster(),
                blocks.selfId(),
                blocks.appVersion(),
                transactionPoolNexus);

        eventCreationManager = new DefaultEventCreationManager(
                blocks.platformContext(), blocks.transactionPoolNexus(), eventCreator);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusEventCreator destroy() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> getEventWindowInputWire() {
        return eventCreationManagerWiring.getInputWire(EventCreationManager::setEventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> getInitialEventWindowInputWire() {
        return staleEventDetectorWiring.getInputWire(StaleEventDetector::setInitialEventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Bytes> getTransactionInputWire() {
        return transactionPoolWiring.getInputWire(TransactionPool::submitSystemTransaction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelchingEventCreationManager() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelchingStaleEventDetector() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flushEventCreationManager() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flushStaleEventDetector() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelchingEventCreationManager() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelchingStaleEventDetector() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Void> getClearEventCreationMangerInputWire() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Void> getClearStaleEventDetectorInputWire() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
