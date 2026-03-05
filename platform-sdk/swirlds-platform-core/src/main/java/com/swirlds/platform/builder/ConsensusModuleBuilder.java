// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.google.common.annotations.VisibleForTesting;
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
import org.apache.logging.log4j.util.Strings;
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
    private static final String CONFIG_PREFIX = "modules.";

    /** Suffix that is stripped from the interface name to derive the config property name. */
    private static final String MODULE_SUFFIX = "Module";

    /**
     * Create a module implementation via {@link ServiceLoader}, selecting by JPMS module name when configured, and
     * enforcing determinism when multiple providers are available.
     *
     * <p>The config property name is derived from the interface's simple name by stripping the
     * {@code "Module"} suffix and lowercasing the first letter (e.g. {@code EventCreatorModule}
     * becomes config key {@code "modules.eventCreator"}).
     *
     * @param <T>            the module interface type
     * @param moduleClass    the module interface class (must end with {@code "Module"})
     * @param configuration  the configuration to read the selected module from
     * @return the selected module instance
     * @throws IllegalStateException if no provider is found, multiple providers exist without explicit selection,
     *                               or the class name does not follow the naming convention
     */
    public static <T> T createModule(@NonNull final Class<T> moduleClass, @NonNull final Configuration configuration) {
        return loadModule(moduleClass, deriveModuleName(moduleClass), configuration);
    }

    /**
     * Derive the config property name from the module interface class name.
     * Strips the {@code "Module"} suffix and lowercases the first letter.
     */
    @VisibleForTesting
    static String deriveModuleName(@NonNull final Class<?> moduleClass) {
        final String simpleName = moduleClass.getSimpleName();
        if (!simpleName.endsWith(MODULE_SUFFIX)) {
            throw new IllegalStateException("Module class name must end with '" + MODULE_SUFFIX + "': " + simpleName);
        }
        final String stripped = simpleName.substring(0, simpleName.length() - MODULE_SUFFIX.length());
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    /**
     * Load a module implementation via {@link ServiceLoader}, selecting by JPMS module name when configured, and
     * enforcing determinism when multiple providers are available.
     */
    private static <T> T loadModule(
            @NonNull final Class<T> moduleClass,
            @NonNull final String moduleName,
            @NonNull final Configuration configuration) {
        final String configKey = CONFIG_PREFIX + moduleName;
        final String selectedModule = configuration.getValue(configKey, String.class, "");
        final List<ServiceLoader.Provider<T>> providers = loadProviders(moduleClass);

        if (providers.isEmpty()) {
            throw new IllegalStateException("No " + moduleName + " implementation found!");
        }

        final T module;
        if (Strings.isEmpty(selectedModule)) {
            if (providers.size() > 1) {
                final String available = providers.stream()
                        .map(ConsensusModuleBuilder::providerModuleName)
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException("Multiple " + moduleName + " providers found ["
                        + available + "] but no explicit selection has been configured. "
                        + "Explicit selection is required to guarantee determinism.");
            }
            module = providers.getFirst().get();
        } else {
            module = providers.stream()
                    .filter(p -> selectedModule.equals(providerModuleName(p)))
                    .findFirst()
                    .map(ServiceLoader.Provider::get)
                    .orElseThrow(() -> {
                        final String available = providers.stream()
                                .map(ConsensusModuleBuilder::providerModuleName)
                                .sorted()
                                .collect(Collectors.joining(", "));
                        return new IllegalStateException("No " + moduleName + " found in module '" + selectedModule
                                + "'. Available: [" + available + "]");
                    });
        }

        log.info(
                DEMO_INFO.getMarker(),
                "Loaded {} module: {} (from {})",
                moduleName,
                module.getClass().getSimpleName(),
                providerModuleName(providers.stream()
                        .filter(p -> p.type().isInstance(module))
                        .findFirst()
                        .orElse(providers.getFirst())));
        return module;
    }

    /**
     * Load all {@link ServiceLoader} providers for the given module interface.
     */
    @VisibleForTesting
    static <T> List<ServiceLoader.Provider<T>> loadProviders(@NonNull final Class<T> moduleClass) {
        return ServiceLoader.load(moduleClass).stream().toList();
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

        final EventCreatorModule eventCreatorModule = createModule(EventCreatorModule.class, configuration);
        eventCreatorModule.initialize(
                model, configuration, metrics, time, random, keysAndCerts, roster, selfId, List::of, () -> false);
        return eventCreatorModule;
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

        final EventIntakeModule eventIntakeModule = createModule(EventIntakeModule.class, configuration);
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

        final PcesModule pcesModule = createModule(PcesModule.class, configuration);
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
        final HashgraphModule hashgraphModule = createModule(HashgraphModule.class, configuration);
        final EventPipelineTracker eventPipelineTracker = null;
        hashgraphModule.initialize(
                model, configuration, metrics, time, roster, selfId, instant -> false, eventPipelineTracker);
        return hashgraphModule;
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
        final GossipModule gossipModule = createModule(GossipModule.class, configuration);
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
}
