// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;

/**
 * Provides {@link VerificationStrategy} instances for use in signature activation tests.
 */
public interface VerificationStrategies {
    /**
     * Returns a {@link VerificationStrategy} to use based on the given sender address, delegate
     * permissions requirements, and Hedera native operations.
     *
     * @param sender the sender address
     * @param requiresDelegatePermission whether the sender is using {@code DELEGATECALL}
     * @param nativeOperations the native Hedera operations
     * @return the {@link VerificationStrategy} to use
     */
    VerificationStrategy activatingOnlyContractKeysFor(
            @NonNull Address sender,
            boolean requiresDelegatePermission,
            @NonNull HederaNativeOperations nativeOperations);
}
