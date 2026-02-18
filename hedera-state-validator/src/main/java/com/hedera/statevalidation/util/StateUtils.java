// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static com.hedera.statevalidation.util.ConfigUtils.getConfiguration;
import static com.hedera.statevalidation.util.ConfigUtils.resetConfiguration;
import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static com.hedera.statevalidation.util.PlatformContextHelper.resetPlatformContext;
import static com.swirlds.platform.state.signed.StartupStateUtils.copyInitialSignedState;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readState;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fixtures.state.FakeStartupNetworks;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.entityid.impl.EntityIdServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServiceMigrator;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.signature.AppSignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.info.FakeNetworkInfo;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.workflows.standalone.ExecutorComponent;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.OneOf;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.ReadableKVStateBase;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Utility for loading and initializing state from disk. Manages the complete initialization
 * lifecycle including constructable registration, service setup, state migrations and State API initialization.
 * Provides singleton access to the deserialized signed state and a handful of utility methods
 * for state-related operations.
 */
@SuppressWarnings({"rawtypes", "resource"})
public final class StateUtils {

    /**
     * Default state key
     */
    private static final String DEFAULT = "DEFAULT";

    private static final Map<String, VirtualMapState> states = new ConcurrentHashMap<>();
    private static final Map<String, Hash> originalStateHashes = new ConcurrentHashMap<>();

    // Static JSON codec cache
    private static final Map<Integer, JsonCodec> keyCodecsById = new ConcurrentHashMap<>();
    private static final Map<Integer, JsonCodec> valueCodecsById = new ConcurrentHashMap<>();

    private StateUtils() {}

    /**
     * Returns <b>mutable</b> instance of {@link VirtualMapState} loaded from disk.
     * @return mutable instance of {@link VirtualMapState}
     */
    public static VirtualMapState getDefaultState() {
        return getState(DEFAULT);
    }

    /**
     * Returns <b>mutable</b> instance of {@link VirtualMapState} loaded from disk for a given key.
     * @param key the key identifying the state
     * @return mutable instance of {@link VirtualMapState}
     */
    public static VirtualMapState getState(String key) {
        if (!states.containsKey(key)) {
            initState(key);
        }
        return states.get(key);
    }

    /**
     * Call to this method resets the state caches
     */
    public static synchronized void resetStateCache() {
        if (states.get(DEFAULT) == null) {
            throw new IllegalStateException("State is not initialized yet");
        }
        VirtualMapStateImpl defaultState = (VirtualMapStateImpl) getDefaultState();
        Set<Map.Entry<String, Map<Integer, StateMetadata<?, ?>>>> serviceEntries =
                defaultState.getServices().entrySet();
        // resetting readable state caches
        for (Map.Entry<String, Map<Integer, StateMetadata<?, ?>>> serviceEntry : serviceEntries) {
            ReadableStates readableStates = defaultState.getReadableStates(serviceEntry.getKey());
            for (Map.Entry<Integer, StateMetadata<?, ?>> stateEntry :
                    serviceEntry.getValue().entrySet()) {
                StateMetadata<?, ?> md = stateEntry.getValue();
                if (md.stateDefinition().keyValue()) {
                    ReadableKVStateBase<?, ?> readableState =
                            (ReadableKVStateBase) readableStates.get(stateEntry.getKey());
                    readableState.reset();
                }
            }
        }
    }

    /**
     * Returns the original hash of the default signed state when it was serialized.
     * <p>
     * Note: This hash may differ from the current hash if the state has been modified since deserialization.
     *
     * @return the original hash from {@link DeserializedSignedState#originalHash()}
     * @see DeserializedSignedState
     */
    public static Hash getOriginalStateHash() {
        if (!originalStateHashes.containsKey(DEFAULT)) {
            initState(DEFAULT);
        }
        return originalStateHashes.get(DEFAULT);
    }

    private static void initState(String key) {
        try {
            resetConfiguration();
            resetPlatformContext();
            registerConstructables();

            final PlatformContext platformContext = getPlatformContext();
            final ServicesRegistryImpl serviceRegistry = initServiceRegistry();
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                    new StateLifecycleManagerImpl(
                            platformContext.getMetrics(),
                            platformContext.getTime(),
                            virtualMap -> new VirtualMapStateImpl(virtualMap, platformContext.getMetrics()),
                            platformContext.getConfiguration());

            final DeserializedSignedState dss =
                    readState(Path.of(ConfigUtils.STATE_DIR).toAbsolutePath(), platformContext, stateLifecycleManager);
            originalStateHashes.put(key, dss.originalHash());

            final SignedState signedState = dss.reservedSignedState().get();

            // need to create copy of the loaded state to make it mutable
            final HashedReservedSignedState hashedSignedState =
                    copyInitialSignedState(signedState, PlatformContextHelper.getPlatformContext());
            final VirtualMapState state = hashedSignedState.state().get().getState();
            states.put(key, state);
            serviceRegistry.register(new RosterServiceImpl(roster -> true, (r, b) -> {}, () -> getState(key), () -> {
                throw new UnsupportedOperationException("No startup networks available");
            }));
            initServiceMigrator(state, platformContext, serviceRegistry);
            state.getRoot().getDataSource().stopAndDisableBackgroundCompaction();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void registerConstructables() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.services");
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.node.app");
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.hapi");
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
        ConstructableRegistry.getInstance().registerConstructables("org.hiero.base");
    }

    /**
     * This method initializes all the services in the registry, and by proxy it initializes all the underlying deserializers
     * to read the state files.
     *
     * @return the initialized services registry
     */
    private static ServicesRegistryImpl initServiceRegistry() {
        final Configuration bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        final Configuration config = getConfiguration();
        final Supplier<Configuration> configSupplier = () -> config;
        final ServicesRegistryImpl servicesRegistry =
                new ServicesRegistryImpl(ConstructableRegistry.getInstance(), config);
        final FakeNetworkInfo fakeNetworkInfo = new FakeNetworkInfo();
        final AppContextImpl appContext = new AppContextImpl(
                InstantSource.system(),
                new AppSignatureVerifier(
                        bootstrapConfig.getConfigData(HederaConfig.class),
                        new SignatureExpanderImpl(),
                        new SignatureVerifierImpl()),
                AppContext.Gossip.UNAVAILABLE_GOSSIP,
                configSupplier,
                fakeNetworkInfo::selfNodeInfo,
                NoOpMetrics::new,
                new AppThrottleFactory(
                        configSupplier, () -> null, () -> ThrottleDefinitions.DEFAULT, ThrottleAccumulator::new),
                () -> UNIVERSAL_NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));

        final AtomicReference<ExecutorComponent> componentRef = new AtomicReference<>();
        Set.of(
                        new EntityIdServiceImpl(),
                        new ConsensusServiceImpl(),
                        new ContractServiceImpl(appContext, new NoOpMetrics()),
                        new FileServiceImpl(),
                        new FreezeServiceImpl(),
                        new ScheduleServiceImpl(appContext),
                        new TokenServiceImpl(appContext),
                        new UtilServiceImpl(appContext, (signedTxn, conf) -> componentRef
                                .get()
                                .transactionChecker()
                                .parseSignedAndCheck(
                                        signedTxn,
                                        config.getConfigData(HederaConfig.class).nodeTransactionMaxBytes())
                                .txBody()),
                        new RecordCacheService(),
                        new BlockRecordService(),
                        new BlockStreamService(),
                        new FeeService(),
                        new CongestionThrottleService(),
                        new NetworkServiceImpl(),
                        new AddressBookServiceImpl(),
                        new HintsServiceImpl(
                                new NoOpMetrics(),
                                ForkJoinPool.commonPool(),
                                appContext,
                                new HintsLibraryImpl(),
                                bootstrapConfig
                                        .getConfigData(BlockStreamConfig.class)
                                        .blockPeriod()),
                        new RosterServiceImpl(roster -> true, (r, b) -> {}, StateUtils::getDefaultState, () -> {
                            throw new UnsupportedOperationException("No startup networks available");
                        }),
                        new PlatformStateService())
                .forEach(servicesRegistry::register);

        return servicesRegistry;
    }

    /**
     * This method performs the migrations on the given state and by proxy initializes the State API.
     * <p>Currently, the {@code previousVersion} and {@code currentVersion} are the same, so migration doesn't
     * happen, instead {@link Schema#restart(MigrationContext)} is called to allow the schema
     * to perform any necessary logic on restart. Most services have nothing to do, but some may need
     * to read files from the disk and could potentially change their state as a result.
     */
    private static void initServiceMigrator(
            @NonNull final State state,
            @NonNull final PlatformContext platformContext,
            @NonNull final ServicesRegistry servicesRegistry) {
        final Configuration configuration = platformContext.getConfiguration();
        final ServiceMigrator serviceMigrator = new OrderedServiceMigrator();
        final SemanticVersion version = creationSoftwareVersionOf(state);
        // previousVersion and currentVersion are the same!
        serviceMigrator.doMigrations(
                (VirtualMapState) state,
                servicesRegistry,
                version,
                version,
                configuration,
                configuration,
                new FakeStartupNetworks(Network.newBuilder().build()),
                new StoreMetricsServiceImpl(new NoOpMetrics()),
                new ConfigProviderImpl(),
                InitTrigger.RESTART);
    }

    // Uses cached JSON codecs
    @SuppressWarnings("unchecked")
    public static String keyToJson(OneOf<StateKey.KeyOneOfType> key) {
        return lookupKeyCodecFor(key).toJSON(key.value());
    }

    // Uses cached JSON codecs
    @SuppressWarnings("unchecked")
    public static String valueToJson(OneOf<StateValue.ValueOneOfType> value) {
        return lookupValueCodecFor(value).toJSON(value.value());
    }

    // Uses cached JSON codecs
    public static JsonCodec lookupKeyCodecFor(OneOf<StateKey.KeyOneOfType> key) {
        return keyCodecsById.computeIfAbsent(key.kind().protoOrdinal(), id -> StateUtils.getCodecFor(key.value()));
    }

    // Uses cached JSON codecs
    public static JsonCodec lookupValueCodecFor(OneOf<StateValue.ValueOneOfType> value) {
        return valueCodecsById.computeIfAbsent(
                value.kind().protoOrdinal(), id -> StateUtils.getCodecFor(value.value()));
    }

    // Extract JSON codec from a pbj object
    public static JsonCodec getCodecFor(@NonNull final Object pbjObject) {
        try {
            final Field jsonCodecField = pbjObject.getClass().getDeclaredField("JSON");
            return (JsonCodec) jsonCodecField.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves a state identifier from the given service name and state key.
     *
     * @param serviceName the name of the service (e.g., "TokenService", "FileService")
     * @param stateKey the key identifying the state within the service (e.g., "ACCOUNTS", "FILES")
     * @return the proto ordinal of the matching state identifier
     * @throws IllegalArgumentException if no state ID is found for the given service name and state key
     */
    public static int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(stateKey);

        final String searchKey = serviceName.toUpperCase() + "_I_" + stateKey.toUpperCase();

        // First try singleton types
        for (final SingletonType singleton : SingletonType.values()) {
            if (singleton.name().equals(searchKey)) {
                return singleton.protoOrdinal();
            }
        }

        // Then try state key types
        for (final StateKey.KeyOneOfType key : StateKey.KeyOneOfType.values()) {
            if (key.name().equals(searchKey)) {
                return key.protoOrdinal();
            }
        }

        throw new IllegalArgumentException(String.format("No state ID found for %s.%s", serviceName, stateKey));
    }
}
