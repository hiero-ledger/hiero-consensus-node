// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.store;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.schedule.impl.WritableScheduleStoreImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * Factory for all writable stores. It creates new writable stores based on the {@link State}.
 *
 * <p>The initial implementation creates all known stores hard-coded. In a future version, this will be replaced by a
 * dynamic approach.
 */
public class WritableStoreFactory {
    // This is the hard-coded part that needs to be replaced by a dynamic approach later,
    // e.g. services have to register their stores
    private static final Map<Class<?>, StoreEntry> STORE_FACTORY = createFactoryMap();

    private static Map<Class<?>, StoreEntry> createFactoryMap() {
        final Map<Class<?>, StoreEntry> newMap = new HashMap<>();
        // AddressBookService
        newMap.put(WritableNodeStore.class, new StoreEntry(AddressBookService.NAME, WritableNodeStore::new));

        // ConsensusService
        newMap.put(WritableTopicStore.class, new StoreEntry(ConsensusService.NAME, WritableTopicStore::new));
        // TokenService
        newMap.put(WritableAccountStore.class, new StoreEntry(TokenService.NAME, WritableAccountStore::new));
        newMap.put(WritableAirdropStore.class, new StoreEntry(TokenService.NAME, WritableAirdropStore::new));
        newMap.put(WritableNftStore.class, new StoreEntry(TokenService.NAME, WritableNftStore::new));
        newMap.put(WritableTokenStore.class, new StoreEntry(TokenService.NAME, WritableTokenStore::new));
        newMap.put(
                WritableTokenRelationStore.class, new StoreEntry(TokenService.NAME, WritableTokenRelationStore::new));
        newMap.put(
                WritableNetworkStakingRewardsStore.class,
                new StoreEntry(
                        TokenService.NAME, (states, entityCounters) -> new WritableNetworkStakingRewardsStore(states)));
        newMap.put(WritableStakingInfoStore.class, new StoreEntry(TokenService.NAME, WritableStakingInfoStore::new));
        // FreezeService
        newMap.put(
                WritableFreezeStore.class,
                new StoreEntry(FreezeService.NAME, (states, entityCounters) -> new WritableFreezeStore(states)));
        // FileService
        newMap.put(WritableFileStore.class, new StoreEntry(FileService.NAME, WritableFileStore::new));
        newMap.put(
                WritableUpgradeFileStore.class,
                new StoreEntry(FileService.NAME, (states, entityCounters) -> new WritableUpgradeFileStore(states)));
        // ContractService
        newMap.put(
                WritableContractStateStore.class,
                new StoreEntry(ContractService.NAME, WritableContractStateStore::new));
        // EntityIdService
        newMap.put(
                WritableEntityIdStore.class,
                new StoreEntry(EntityIdService.NAME, (states, entityCounters) -> new WritableEntityIdStore(states)));
        // Schedule Service
        newMap.put(WritableScheduleStore.class, new StoreEntry(ScheduleService.NAME, WritableScheduleStoreImpl::new));
        // Roster Service
        newMap.put(
                WritableRosterStore.class,
                new StoreEntry(RosterService.NAME, (states, entityCounters) -> new WritableRosterStore(states)));
        // HintsService
        newMap.put(WritableHintsStore.class, new StoreEntry(HintsService.NAME, WritableHintsStoreImpl::new));
        newMap.put(
                WritableHistoryStore.class,
                new StoreEntry(HistoryService.NAME, (states, entityCounters) -> new WritableHistoryStoreImpl(states)));
        return Collections.unmodifiableMap(newMap);
    }

    private final String serviceName;
    private final WritableStates states;
    private final WritableEntityCounters entityCounters;

    /**
     * Constructor of {@code WritableStoreFactory}
     *
     * @param state       the {@link State} to use
     * @param serviceName the name of the service to create stores for
     * @param entityCounters the {@link WritableEntityCounters} to use
     * @throws NullPointerException     if one of the arguments is {@code null}
     * @throws IllegalArgumentException if the service name is unknown
     */
    public WritableStoreFactory(
            @NonNull final State state,
            @NonNull final String serviceName,
            @NonNull final WritableEntityCounters entityCounters) {
        requireNonNull(state);
        this.serviceName = requireNonNull(serviceName, "The argument 'serviceName' cannot be null!");
        this.states = state.getWritableStates(serviceName);
        this.entityCounters = requireNonNull(entityCounters);
    }

    /**
     * Create a new store given the store's interface. This gives read and write access to the store.
     *
     * @param <C>            Interface class for a Store
     * @param storeInterface The store interface to find and create a store for
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException     if {@code storeInterface} is {@code null}
     */
    @NonNull
    public <C> C getStore(@NonNull final Class<C> storeInterface) throws IllegalArgumentException {
        requireNonNull(storeInterface, "The supplied argument 'storeInterface' cannot be null!");
        final var entry = STORE_FACTORY.get(storeInterface);
        if (entry != null && serviceName.equals(entry.name())) {
            final var store = entry.factory().create(states, entityCounters);
            if (!storeInterface.isInstance(store)) {
                throw new IllegalArgumentException("No instance " + storeInterface
                        + " is available"); // This needs to be ensured while stores are registered
            }
            return storeInterface.cast(store);
        }
        throw new IllegalArgumentException("No store of the given class is available " + storeInterface.getName());
    }

    /**
     * Gets the name of the service this factory is creating stores for.
     *
     * @return the name of the service
     */
    public String getServiceName() {
        return serviceName;
    }

    private interface StoreFactory {
        Object create(@NonNull WritableStates states, @NonNull WritableEntityCounters entityCounters);
    }

    private record StoreEntry(@NonNull String name, @NonNull StoreFactory factory) {}
}
