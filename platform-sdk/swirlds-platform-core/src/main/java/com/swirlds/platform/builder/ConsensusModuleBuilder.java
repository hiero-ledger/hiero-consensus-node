// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatusAction;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * A builder for consensus modules using the ServiceLoader mechanism.
 *
 * <p>Module selection is driven by {@link ModulesConfig}. When a config property is set, the
 * provider whose JPMS module name matches the property value is selected. When the property is
 * empty and only one provider exists, that provider is used. When the property is empty but
 * multiple providers exist, an {@link IllegalStateException} is thrown to prevent
 * non-deterministic selection across nodes.
 */
public class ConsensusModuleBuilder {

    private static final Logger log = LogManager.getLogger(ConsensusModuleBuilder.class);

    private ConsensusModuleBuilder() {}

    /** Prefix for all module selection config properties. */
    static final String CONFIG_PREFIX = "modules.";

    /**
     * Load a module implementation via {@link ServiceLoader}, selecting by JPMS module name when configured, and
     * enforcing determinism when multiple providers are available.
     *
     * <p>The config key is derived from the{@code moduleName} parameter.
     *
     * @param <T>            the module interface type
     * @param moduleClass    the module interface class
     * @param moduleName     the module name that will be matched against the config property name (e.g. "eventCreator" -> "modules.eventCreator")
     * @param configuration  the configuration to read the selected module from
     * @return the selected module instance
     * @throws IllegalStateException if no provider is found, or multiple providers exist without explicit selection
     */
    private static <T> T loadModule(
            @NonNull final Class<T> moduleClass,
            @NonNull final String moduleName,
            @NonNull final Configuration configuration) {
        final String selectedModule = configuration.getValue(moduleConfigFromName(moduleName), String.class, "");
        final List<NamedProvider<T>> providers = ServiceLoader.load(moduleClass).stream()
                .map(p -> new NamedProvider<>(providerModuleName(p), p))
                .toList();
        return selectModule(providers, moduleName, selectedModule);
    }

    private static String moduleConfigFromName(final String moduleName) {
        return CONFIG_PREFIX + moduleName;
    }

    /**
     * Get a stable identifier for a ServiceLoader provider. Uses the JPMS module name when
     * available; falls back to the provider class's package name for classpath (unnamed module)
     * environments such as containers.
     */
    private static <T> String providerModuleName(@NonNull final ServiceLoader.Provider<T> provider) {
        final String jpmsName = provider.type().getModule().getName();
        return jpmsName != null ? jpmsName : provider.type().getPackageName();
    }

    /**
     * A named module provider: pairs a JPMS module name with a lazy instance supplier.
     *
     * @param <T>        the module interface type
     * @param moduleName the JPMS module name that identifies this provider
     * @param factory    a supplier that creates the module instance
     */
    record NamedProvider<T>(
            @NonNull String moduleName, @NonNull Supplier<T> factory) {}

    /**
     * Select a module implementation from a list of named providers, applying config selection
     *
     * <p>@visibleForTesting: This method is package-private to allow direct testing of the selection without requiring actual ServiceLoader discovery.
     *
     * @param <T>            the module interface type
     * @param providers      the list of available providers
     * @param moduleName     the name of the module (e.g. "eventCreator")
     * @param selectedModule the JPMS module name to select, or empty for default
     * @return the selected module instance
     * @throws IllegalStateException if no provider is found, or multiple providers exist without explicit selection
     */
    static <T> T selectModule(
            @NonNull final List<NamedProvider<T>> providers,
            @NonNull final String moduleName,
            @NonNull final String selectedModule) {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No " + moduleName + " implementation found!");
        }

        if (selectedModule.isEmpty()) {
            if (providers.size() > 1) {
                final String available = providers.stream()
                        .map(NamedProvider::moduleName)
                        .sorted()
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException("Multiple " + moduleName + " providers found ["
                        + available + "] but no explicit selection has been configured. "
                        + "Explicit selection is required to guarantee determinism.");
            }
            return providers.getFirst().factory().get();
        }

        return providers.stream()
                .filter(p -> selectedModule.equals(p.moduleName()))
                .findFirst()
                .map(p -> p.factory().get())
                .orElseThrow(() -> {
                    final String available = providers.stream()
                            .map(NamedProvider::moduleName)
                            .sorted()
                            .collect(Collectors.joining(", "));
                    return new IllegalStateException("No " + moduleName + " found in module '" + selectedModule
                            + "'. Available: [" + available + "]");
                });
    }

    /**
     * Create an instance of the {@link EventCreatorModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code EventCreatorModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    public static EventCreatorModule createEventCreatorModule(@NonNull final Configuration configuration) {
        return loadModule(EventCreatorModule.class, EventCreatorModule.NAME, configuration);
    }

    /**
     * Create and initialize a no-op instance of the {@link EventCreatorModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code EventCreatorModule}
     */
    public static EventCreatorModule createNoOpEventCreatorModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final SecureRandom random = new SecureRandom();
        final KeysAndCerts keysAndCerts;
        try {
            keysAndCerts = KeysAndCertsGenerator.generate(selfId, SigningSchema.ED25519, random, random);
        } catch (final Exception e) {
            throw new RuntimeException("Exception thrown while creating dummy KeysAndCerts", e);
        }
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));

        final EventCreatorModule eventCreatorModule = createEventCreatorModule(configuration);
        eventCreatorModule.initialize(
                model, configuration, metrics, time, random, keysAndCerts, roster, selfId, List::of, () -> false);
        return eventCreatorModule;
    }

    /**
     * Create an instance of the {@link EventIntakeModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code EventIntakeModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    public static EventIntakeModule createEventIntakeModule(@NonNull final Configuration configuration) {
        return loadModule(EventIntakeModule.class, EventIntakeModule.NAME, configuration);
    }

    /**
     * Create and initialize a no-op instance of the {@link EventIntakeModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code EventIntakeModule}
     */
    public static EventIntakeModule createNoOpEventIntakeModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));
        final RosterHistory rosterHistory =
                new RosterHistory(List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, roster));
        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final TransactionLimits transactionLimits = new TransactionLimits(0, 0);
        final EventPipelineTracker eventPipelineTracker = null;

        final EventIntakeModule eventIntakeModule = createEventIntakeModule(configuration);
        eventIntakeModule.initialize(
                model,
                configuration,
                metrics,
                time,
                rosterHistory,
                intakeEventCounter,
                transactionLimits,
                eventPipelineTracker);
        return eventIntakeModule;
    }

    /**
     * Create an instance of the {@link PcesModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code PcesModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    @NonNull
    public static PcesModule createPcesModule(@NonNull final Configuration configuration) {
        return loadModule(PcesModule.class, PcesModule.NAME, configuration);
    }

    /**
     * Create and initialize a no-op instance of the {@link PcesModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code PcesModule}
     */
    @NonNull
    public static PcesModule createNoOpPcesModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RecycleBin recycleBin = new SimpleRecycleBin();
        final long startingRound = 0L;
        final Runnable flushIntake = () -> {};
        final Runnable flushTransactionHandling = () -> {};
        final Supplier<ReservedSignedState> latestImmutableStateSupplier = ReservedSignedState::createNullReservation;
        final Consumer<PlatformStatusAction> statusActionConsumer = status -> {};
        final Runnable stateHasherFlusher = () -> {};
        final Runnable signalEndOfPcesReplay = () -> {};
        final EventPipelineTracker eventPipelineTracker = null;

        final PcesModule pcesModule = createPcesModule(configuration);
        pcesModule.initialize(
                model,
                configuration,
                metrics,
                time,
                selfId,
                recycleBin,
                startingRound,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                statusActionConsumer,
                stateHasherFlusher,
                signalEndOfPcesReplay,
                eventPipelineTracker);
        return pcesModule;
    }

    /**
     * Create an instance of the {@link HashgraphModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code HashgraphModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    public static HashgraphModule createHashgraphModule(@NonNull final Configuration configuration) {
        return loadModule(HashgraphModule.class, HashgraphModule.NAME, configuration);
    }

    /**
     * Create and initialize a no-op instance of the {@link HashgraphModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code HashgraphModule}
     */
    public static HashgraphModule createNoOpHashgraphModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));
        final HashgraphModule hashgraphModule = createHashgraphModule(configuration);
        final EventPipelineTracker eventPipelineTracker = null;
        hashgraphModule.initialize(
                model, configuration, metrics, time, roster, selfId, instant -> false, eventPipelineTracker);
        return hashgraphModule;
    }

    /**
     * Create an instance of the {@link GossipModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code GossipModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    @NonNull
    public static GossipModule createGossipModule(@NonNull final Configuration configuration) {
        return loadModule(GossipModule.class, GossipModule.NAME, configuration);
    }

    /**
     * Create and initialize a no-op instance of the {@link GossipModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code GossipModule}
     */
    @NonNull
    public static GossipModule createNoOpGossipModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final KeysAndCerts keysAndCerts;
        final Bytes certificate;
        try {
            keysAndCerts = KeysAndCertsGenerator.generate(selfId);
            certificate = Bytes.wrap(keysAndCerts.sigCert().getEncoded());
        } catch (final GeneralSecurityException | KeyGeneratingException e) {
            // These exceptions should not occur since we are using default values
            throw new RuntimeException(e);
        }
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, certificate, List.of(ServiceEndpoint.DEFAULT));
        final Roster roster = new Roster(List.of(rosterEntry));
        final SemanticVersion appVersion = SemanticVersion.DEFAULT;
        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final Supplier<ReservedSignedState> latestCompleteStateSupplier = ReservedSignedState::createNullReservation;
        final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise =
                new BlockingResourceProvider<>();
        final FallenBehindMonitor fallenBehindMonitor = new FallenBehindMonitor(roster, configuration, metrics);
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(metrics, time, configuration);
        final GossipModule gossipModule = createGossipModule(configuration);
        gossipModule.initialize(
                model,
                configuration,
                metrics,
                time,
                keysAndCerts,
                roster,
                selfId,
                appVersion,
                intakeEventCounter,
                latestCompleteStateSupplier,
                reservedSignedStateResultPromise,
                fallenBehindMonitor,
                stateLifecycleManager);
        return gossipModule;
    }

    /**
     * Create an instance of the {@link ReconnectModule} using {@link ServiceLoader}.
     *
     * @param configuration the configuration containing module selection properties
     * @return an instance of {@code ReconnectModule}
     * @throws IllegalStateException if no implementation is found or selection is ambiguous
     */
    @NonNull
    public static ReconnectModule createReconnectModule(@NonNull final Configuration configuration) {
        return loadModule(ReconnectModule.class, ReconnectModule.NAME, configuration);
    }
}
