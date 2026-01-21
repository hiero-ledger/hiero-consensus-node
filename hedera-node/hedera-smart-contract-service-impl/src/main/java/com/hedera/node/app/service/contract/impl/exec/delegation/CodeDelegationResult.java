// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.delegation;

import static org.hyperledger.besu.datatypes.CodeDelegation.PER_AUTH_BASE_COST;

import java.util.Set;
import org.hyperledger.besu.collections.trie.BytesTrieSet;
import org.hyperledger.besu.datatypes.Address;

/**
 * Class that contains the results when delegating EIP-7702 transaction.
 */
public class CodeDelegationResult {
    private long availableGas;
    private final Set<Address> accessedDelegatorAddresses = new BytesTrieSet<>(Address.SIZE);
    private long alreadyExistingDelegators = 0L;

    public CodeDelegationResult(final long availableGas) {
        this.availableGas = availableGas;
    }

    public void addAccessedDelegatorAddress(final Address address) {
        accessedDelegatorAddresses.add(address);
    }

    public void incrementAlreadyExistingDelegators() {
        this.alreadyExistingDelegators += 1;
    }

    public Set<Address> accessedDelegatorAddresses() {
        return accessedDelegatorAddresses;
    }

    public long alreadyExistingDelegators() {
        return alreadyExistingDelegators;
    }

    public void deductGas(final long gasUsed) {
        this.availableGas -= gasUsed;
    }

    public long getAvailableGas() {
        return availableGas;
    }

    public long getRefund() {
        return alreadyExistingDelegators * PER_AUTH_BASE_COST;
    }
}
