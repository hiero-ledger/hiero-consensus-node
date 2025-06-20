// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.services.stream.proto.SidecarType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Tracks storage accesses for a particular transaction.
 */
public class StorageAccessTracker {
    private static final Function<ContractID, Map<UInt256, StorageAccess>> MAP_FACTORY = ignored -> new TreeMap<>();
    private final Map<ContractID, Map<UInt256, StorageAccess>> accessesByContract =
            new TreeMap<>(HapiUtils.CONTRACT_ID_COMPARATOR);

    /**
     * The first time this method is called for a particular {@link SlotKey}, tracks its
     * value for future reporting in a {@link SidecarType#CONTRACT_STATE_CHANGE} sidecar.
     *
     * @param contractID the id of the contract whose storage is being read
     * @param key the key of the slot read
     * @param value the value read
     */
    public void trackIfFirstRead(
            final ContractID contractID, @NonNull final UInt256 key, @NonNull final UInt256 value) {
        accessesByContract.computeIfAbsent(contractID, MAP_FACTORY).putIfAbsent(key, StorageAccess.newRead(key, value));
    }

    /**
     * Returns the list of all storage reads (i.e. the tracked {@code SLOAD}'s).
     * This is a convenience methods equivalent to passing an empty list to
     * {@link #getReadsMergedWith(List)}
     *
     * @return the list of all storage reads
     */
    public List<StorageAccesses> getJustReads() {
        return getReadsMergedWith(List.of());
    }

    /**
     * Given all the storage writes from a transaction, returns the merged list of {@link StorageAccesses} that
     * includes both the given list of writes, and all tracked first reads.
     *
     * <p>If a key was both read and overwritten, the read-only access will not appear,
     * since a write {@link com.hedera.node.app.service.contract.impl.state.StorageAccess}
     * already includes the overwritten value.
     *
     * @param writes all the storage
     * @return the merged list of all storage accesses
     */
    public List<StorageAccesses> getReadsMergedWith(@NonNull final List<StorageAccesses> writes) {
        writes.forEach(scoped -> {
            final var reads = accessesByContract.computeIfAbsent(scoped.contractID(), MAP_FACTORY);
            scoped.accesses().forEach(write -> reads.put(write.key(), write));
        });
        final List<StorageAccesses> allAccesses = new ArrayList<>();
        accessesByContract.forEach((contract, accesses) -> {
            final var scopedAccesses = new ArrayList<>(accesses.values());
            allAccesses.add(new StorageAccesses(contract, scopedAccesses));
        });
        return allAccesses;
    }
}
