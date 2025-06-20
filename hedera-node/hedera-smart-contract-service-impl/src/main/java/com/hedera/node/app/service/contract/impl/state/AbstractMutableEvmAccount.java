// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.NavigableMap;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.AccountStorageEntry;
import org.hyperledger.besu.evm.account.MutableAccount;

/**
 * Provides implementation support for Hedera accounts by overriding the unsupported Besu API methods
 * to throw {@link UnsupportedOperationException}. These methods are,
 * <ul>
 *     <li>{@link Account#getAddressHash()} - We don't store accounts in state by hash.</li>
 *     <li>{@link Account#storageEntriesFrom(Bytes32, int)}} - As above.</li>
 *     <li>{@link MutableAccount#setBalance(Wei)} - Message call processors should dispatch value transfers.</li>
 *     <li>{@link MutableAccount#clearStorage()} - A re-used {@code CREATE2} address will be a different account.</li>
 *     <li>{@link MutableAccount#getUpdatedStorage()} - The {@link EvmFrameState} manages transaction boundaries.</li>
 *  </ul>
 */
public abstract class AbstractMutableEvmAccount implements MutableAccount, HederaEvmAccount {
    /**
     * Unlike in Besu, we don't store the address hash in state (c.f. {@link Account#getAddressHash()} javadoc);
     * and also don't support {@link org.hyperledger.besu.evm.worldstate.WorldState#streamAccounts(Bytes32, int)}. So
     * there is actually no reason to implement this method in Hedera.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public Hash getAddressHash() {
        throw new UnsupportedOperationException("getAddressHash");
    }

    /**
     * Besu uses this method for storage validation in test, but we don't have a practical way to implement it
     * at the moment, since this would require iterating through the storage linked list.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(Bytes32 startKeyHash, int limit) {
        throw new UnsupportedOperationException("storageEntriesFrom");
    }

    /**
     * Besu uses this method to do zero-sum balance changes, but since the {@link ContractService} neither
     * owns account state nor is aware of receiver signature requirements, it's more sensible to require
     * message call processors to use {@link HandleHederaNativeOperations#transferWithReceiverSigCheck(long, AccountID, AccountID, VerificationStrategy)}.
     *
     * @param value the amount to set
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setBalance(@NonNull final Wei value) {
        throw new UnsupportedOperationException("Balance changes should be done via dispatch");
    }

    /**
     * Besu uses this method to clear storage when re-using a {@code CREATE2} address after the contract
     * at that address has called {@code selfdestruct}. But in our system, a re-used {@code CREATE2}
     * address will refer to a different account with a different number, so this method is not needed;
     * and in fact should not be implemented, since the expiration "system task" is already responsible
     * for purging storage from deleted contracts.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void clearStorage() {
        throw new UnsupportedOperationException("Storage is purged by the expiration system task");
    }

    /**
     * Besu uses this method to propagate storage changes across a {@code commit()} boundary; but we
     * don't need to do that in this module, since the {@link EvmFrameState} manages transaction
     * boundaries already.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public Map<UInt256, UInt256> getUpdatedStorage() {
        throw new UnsupportedOperationException("Storage changes are managed by the EvmFrameState");
    }
}
