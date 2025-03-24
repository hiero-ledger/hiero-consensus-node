// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.ReadableBlockRecordStore;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.ReadableContractStateStore;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.ReadableUpgradeFileStoreImpl;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.ReadableFreezeStoreImpl;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ReadableScheduleStoreImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableAirdropStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNftStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.service.ReadableRosterStore;
import com.swirlds.platform.state.service.ReadableRosterStoreImpl;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Factory for all readable stores. It creates new readable stores based on the {@link State}.
 *
 * <p>The initial implementation creates all known stores hard-coded. In a future version, this will be replaced by a
 * dynamic approach.
 */
public class ReadableStoreFactory {
    // This is the hard-coded part that needs to be replaced by a dynamic approach later,
    // e.g. services have to register their stores
    private static final Map<Class<?>, StoreEntry> STORE_FACTORY = createFactoryMap();

    private static Map<Class<?>, StoreEntry> createFactoryMap() {
        Map<Class<?>, StoreEntry> newMap = new HashMap<>();
        // Tokens and accounts
        newMap.put(ReadableAccountStore.class, new StoreEntry(TokenService.NAME, ReadableAccountStoreImpl::new));
        newMap.put(ReadableAirdropStore.class, new StoreEntry(TokenService.NAME, ReadableAirdropStoreImpl::new));
        newMap.put(ReadableNftStore.class, new StoreEntry(TokenService.NAME, ReadableNftStoreImpl::new));
        newMap.put(
                ReadableStakingInfoStore.class,
                new StoreEntry(
                        TokenService.NAME, (states, entityCounters) -> new ReadableStakingInfoStoreImpl(states)));
        newMap.put(ReadableTokenStore.class, new StoreEntry(TokenService.NAME, ReadableTokenStoreImpl::new));
        newMap.put(
                ReadableTokenRelationStore.class,
                new StoreEntry(TokenService.NAME, ReadableTokenRelationStoreImpl::new));
        newMap.put(
                ReadableNetworkStakingRewardsStore.class,
                new StoreEntry(
                        TokenService.NAME,
                        (states, entityCounters) -> new ReadableNetworkStakingRewardsStoreImpl(states)));
        // Topics
        newMap.put(ReadableTopicStore.class, new StoreEntry(ConsensusService.NAME, ReadableTopicStoreImpl::new));
        // Schedules
        newMap.put(ReadableScheduleStore.class, new StoreEntry(ScheduleService.NAME, ReadableScheduleStoreImpl::new));
        // Files
        newMap.put(ReadableFileStore.class, new StoreEntry(FileService.NAME, ReadableFileStoreImpl::new));
        newMap.put(
                ReadableUpgradeFileStore.class,
                new StoreEntry(FileService.NAME, (states, entityCounters) -> new ReadableUpgradeFileStoreImpl(states)));
        // Network Admin
        newMap.put(
                ReadableFreezeStore.class,
                new StoreEntry(FreezeService.NAME, (states, entityCounters) -> new ReadableFreezeStoreImpl(states)));
        // Contracts
        newMap.put(ContractStateStore.class, new StoreEntry(ContractService.NAME, ReadableContractStateStore::new));
        // Block Records
        newMap.put(
                ReadableBlockRecordStore.class,
                new StoreEntry(
                        BlockRecordService.NAME, (states, entityCounters) -> new ReadableBlockRecordStore(states)));
        newMap.put(ReadableNodeStore.class, new StoreEntry(AddressBookService.NAME, ReadableNodeStoreImpl::new));
        // Platform
        newMap.put(
                ReadablePlatformStateStore.class,
                new StoreEntry(
                        PlatformStateService.NAME, (states, entityCounters) -> new ReadablePlatformStateStore(states)));
        newMap.put(
                ReadableRosterStore.class,
                new StoreEntry(RosterService.NAME, (states, entityCounters) -> new ReadableRosterStoreImpl(states)));
        // Entity ids
        newMap.put(
                ReadableEntityIdStore.class,
                new StoreEntry(
                        EntityIdService.NAME, (states, entityCounters) -> new ReadableEntityIdStoreImpl(states)));
        // Hints service
        newMap.put(
                ReadableHintsStore.class,
                new StoreEntry(HintsService.NAME, (states, entityCounters) -> new ReadableHintsStoreImpl(states)));
        // History service
        newMap.put(
                ReadableHistoryStore.class,
                new StoreEntry(HistoryService.NAME, (states, entityCounters) -> new ReadableHistoryStoreImpl(states)));
        return Collections.unmodifiableMap(newMap);
    }

    private final State state;
    private final Function<SemanticVersion, SoftwareVersion> versionFactory;

    /**
     * Constructor of {@code ReadableStoreFactory}
     *
     * @param state the {@link State} to use
     */
    public ReadableStoreFactory(
            @NonNull final State state, @NonNull final Function<SemanticVersion, SoftwareVersion> versionFactory) {
        this.state = requireNonNull(state, "The supplied argument 'state' cannot be null!");
        this.versionFactory = requireNonNull(versionFactory, "The supplied argument 'versionFactory' cannot be null!");
    }

    /**
     * Create a new store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <C>            Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException     if {@code storeInterface} is {@code null}
     */
    @NonNull
    public <C> C getStore(@NonNull final Class<C> storeInterface) throws IllegalArgumentException {
        requireNonNull(storeInterface, "The supplied argument 'storeInterface' cannot be null!");
        final var entry = STORE_FACTORY.get(storeInterface);
        if (entry != null) {
            final var readableStates = state.getReadableStates(entry.name);
            final var readableEntityIdStore =
                    new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
            final var store = entry.createFrom(readableStates, readableEntityIdStore);
            if (!storeInterface.isInstance(store)) {
                throw new IllegalArgumentException("No instance " + storeInterface
                        + " is available"); // This needs to be ensured while stores are registered
            }
            if (store instanceof ReadablePlatformStateStore readablePlatformStateStore) {
                readablePlatformStateStore.setVersionFactory(versionFactory);
            }
            return storeInterface.cast(store);
        }
        throw new IllegalArgumentException("No store of class " + storeInterface + " is available");
    }

    private record StoreEntry(
            @NonNull String name, @Nullable BiFunction<ReadableStates, ReadableEntityCounters, ?> fromStates) {
        private StoreEntry {
            requireNonNull(name);
            requireNonNull(fromStates);
        }

        @SuppressWarnings("unchecked")
        public <T> T createFrom(
                @NonNull final ReadableStates readableStates, @NonNull ReadableEntityCounters entityCounters) {
            requireNonNull(readableStates);
            return (T) fromStates.apply(readableStates, entityCounters);
        }
    }
}
