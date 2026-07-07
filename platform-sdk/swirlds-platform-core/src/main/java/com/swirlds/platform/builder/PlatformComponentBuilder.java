// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.state.signed.DefaultSignedStateSentinel;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.system.DefaultPlatformMonitor;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.PlatformMonitor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.DefaultConsensusEventStream;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.management.persistence.DefaultStateSnapshotManager;
import org.hiero.consensus.state.management.persistence.StateSnapshotManager;
import org.hiero.consensus.state.signed.DefaultStateGarbageCollector;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateGarbageCollector;

/**
 * The advanced platform builder is responsible for constructing platform components. This class is exposed so that
 * individual components can be replaced with alternate implementations.
 * <p>
 * In order to be considered a "component", an object must meet the following criteria:
 * <ul>
 *     <li>A component must not require another component as a constructor argument.</li>
 *     <li>A component's constructor should only use things from the {@link PlatformBuildingBlocks} or things derived
 *     from things from the {@link PlatformBuildingBlocks}.</li>
 *     <li>A component must not communicate with other components except through the wiring framework
 *         (with a very small number of exceptions due to tech debt that has not yet been paid off).</li>
 *     <li>A component should have an interface and at default implementation.</li>
 *     <li>A component should use {@link ComponentWiring ComponentWiring} to define
 *         wiring API.</li>
 *     <li>The order in which components are constructed should not matter.</li>
 *     <li>A component must not be a static singleton or use static stateful variables in any way.</li>
 * </ul>
 */
public class PlatformComponentBuilder {

    private final PlatformBuildingBlocks blocks;

    private StateGarbageCollector stateGarbageCollector;
    private ConsensusEventStream consensusEventStream;
    private SignedStateSentinel signedStateSentinel;
    private PlatformMonitor platformMonitor;
    private StateSnapshotManager stateSnapshotManager;

    private SwirldsPlatform swirldsPlatform;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used;

    /**
     * Constructor.
     *
     * @param blocks the build context for the platform under construction, contains all data needed to construct
     * platform components
     */
    public PlatformComponentBuilder(@NonNull final PlatformBuildingBlocks blocks) {
        this.blocks = Objects.requireNonNull(blocks);
    }

    /**
     * Get the build context for this platform. Contains all data needed to construct platform components.
     *
     * @return the build context
     */
    @NonNull
    public PlatformBuildingBlocks getBuildingBlocks() {
        return blocks;
    }

    /**
     * Throw an exception if this builder has been used to build a platform or a platform factory.
     */
    private void throwIfAlreadyUsed() {
        if (used) {
            throw new IllegalStateException("PlatformBuilder has already been used");
        }
    }

    /**
     * Build the platform.
     *
     * @return the platform
     */
    @NonNull
    public Platform build() {
        throwIfAlreadyUsed();
        used = true;

        try (final ReservedSignedState ignored = blocks.initialState()) {
            swirldsPlatform = new SwirldsPlatform(this);
            return swirldsPlatform;
        } finally {
            getMetricsProvider().start();
        }
    }

    /**
     * Provide a state garbage collector in place of the platform's default state garbage collector.
     *
     * @param stateGarbageCollector the state garbage collector to use
     * @return this builder
     */
    public PlatformComponentBuilder withStateGarbageCollector(
            @NonNull final StateGarbageCollector stateGarbageCollector) {
        throwIfAlreadyUsed();
        if (this.stateGarbageCollector != null) {
            throw new IllegalStateException("State garbage collector has already been set");
        }
        this.stateGarbageCollector = Objects.requireNonNull(stateGarbageCollector);
        return this;
    }

    /**
     * Build the state garbage collector if it has not yet been built. If one has been provided via
     * {@link #withStateGarbageCollector(StateGarbageCollector)}, that garbage collector will be used. If this method is
     * called more than once, only the first call will build the state garbage collector. Otherwise, the default garbage
     * collector will be created and returned.
     *
     * @return the state garbage collector
     */
    @NonNull
    public StateGarbageCollector buildStateGarbageCollector() {
        if (stateGarbageCollector == null) {
            stateGarbageCollector =
                    new DefaultStateGarbageCollector(blocks.platformContext().getMetrics());
        }
        return stateGarbageCollector;
    }

    /**
     * Provide a consensus event stream in place of the platform's default consensus event stream.
     *
     * @param consensusEventStream the consensus event stream to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withConsensusEventStream(@NonNull final ConsensusEventStream consensusEventStream) {
        throwIfAlreadyUsed();
        if (this.consensusEventStream != null) {
            throw new IllegalStateException("Consensus event stream has already been set");
        }
        this.consensusEventStream = Objects.requireNonNull(consensusEventStream);
        return this;
    }

    /**
     * Build the consensus event stream if it has not yet been built. If one has been provided via
     * {@link #withConsensusEventStream(ConsensusEventStream)}, that stream will be used. If this method is called more
     * than once, only the first call will build the consensus event stream. Otherwise, the default stream will be
     * created and returned.
     *
     * @return the consensus event stream
     */
    @NonNull
    public ConsensusEventStream buildConsensusEventStream() {
        if (consensusEventStream == null) {
            final PlatformContext platformContext = blocks.platformContext();
            consensusEventStream = new DefaultConsensusEventStream(
                    platformContext.getTime(),
                    platformContext.getConfiguration(),
                    platformContext.getMetrics(),
                    blocks.selfId(),
                    (byte[] data) -> new PlatformSigner(blocks.keysAndCerts()).sign(data),
                    blocks.consensusEventStreamName(),
                    (CesEvent event) -> event.isLastInRoundReceived()
                            && blocks.freezeChecker()
                                    .isInFreezePeriod(event.getPlatformEvent().getConsensusTimestamp()));
        }
        return consensusEventStream;
    }

    /**
     * Provide a platform monitor in place of the platform's default platform monitor.
     *
     * @param platformMonitor the platform monitor to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withPlatformMonitor(@NonNull final PlatformMonitor platformMonitor) {
        throwIfAlreadyUsed();
        if (this.platformMonitor != null) {
            throw new IllegalStateException("Status state machine has already been set");
        }
        this.platformMonitor = Objects.requireNonNull(platformMonitor);
        return this;
    }

    /**
     * Build the platform monitor if it has not yet been built. If one has been provided via
     * {@link #withPlatformMonitor(PlatformMonitor)}, that platform monitor will be used. If this method is called
     * more than once, only the first call will build the platform monitor. Otherwise, the default platform monitor
     * will be created and returned.
     *
     * @return the platform monitor
     */
    @NonNull
    public PlatformMonitor buildPlatformMonitor() {
        if (platformMonitor == null) {
            platformMonitor = new DefaultPlatformMonitor(blocks.platformContext(), blocks.selfId());
        }
        return platformMonitor;
    }

    /**
     * Provide a signed state sentinel in place of the platform's default signed state sentinel.
     *
     * @param signedStateSentinel the signed state sentinel to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withSignedStateSentinel(@NonNull final SignedStateSentinel signedStateSentinel) {
        throwIfAlreadyUsed();
        if (this.signedStateSentinel != null) {
            throw new IllegalStateException("Signed state sentinel has already been set");
        }
        this.signedStateSentinel = Objects.requireNonNull(signedStateSentinel);
        return this;
    }

    /**
     * Build the signed state sentinel if it has not yet been built. If one has been provided via
     * {@link #withSignedStateSentinel(SignedStateSentinel)}, that sentinel will be used. If this method is called more
     * than once, only the first call will build the signed state sentinel. Otherwise, the default sentinel will be
     * created and returned.
     *
     * @return the signed state sentinel
     */
    @NonNull
    public SignedStateSentinel buildSignedStateSentinel() {
        if (signedStateSentinel == null) {
            signedStateSentinel = new DefaultSignedStateSentinel(blocks.platformContext());
        }
        return signedStateSentinel;
    }

    /**
     * Provide a state snapshot manager in place of the platform's default state snapshot manager.
     *
     * @param stateSnapshotManager the state snapshot manager to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withStateSnapshotManager(@NonNull final StateSnapshotManager stateSnapshotManager) {
        throwIfAlreadyUsed();
        if (this.stateSnapshotManager != null) {
            throw new IllegalStateException("State snapshot manager has already been set");
        }
        this.stateSnapshotManager = Objects.requireNonNull(stateSnapshotManager);
        return this;
    }

    /**
     * Build the state snapshot manager if it has not yet been built. If one has been provided via
     * {@link #withStateSnapshotManager(StateSnapshotManager)}, that manager will be used. If this method is called more
     * than once, only the first call will build the state snapshot manager. Otherwise, the default manager will be
     * created and returned.
     *
     * @return the state snapshot manager
     */
    @NonNull
    public StateSnapshotManager buildStateSnapshotManager() {
        if (stateSnapshotManager == null) {
            final StateConfig stateConfig =
                    blocks.platformContext().getConfiguration().getConfigData(StateConfig.class);
            final String actualMainClassName = stateConfig.getMainClassName(blocks.mainClassName());

            stateSnapshotManager = new DefaultStateSnapshotManager(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.platformContext().getTime(),
                    blocks.platformContext().getFileSystemManager(),
                    actualMainClassName,
                    blocks.selfId(),
                    blocks.swirldName(),
                    blocks.stateLifecycleManager());
        }
        return stateSnapshotManager;
    }
}
