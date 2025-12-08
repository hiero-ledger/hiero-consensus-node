// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Additional API for the Smart Contract Service beyond its dispatchable handlers.
 * <p>Currently only used by the token service.
 */
public interface ContractServiceApi {
    /**
     * Sets the account identified by the accountID with the associated runtime bytecode in the ContractStateStore.
     *
     * @param accountID id of the account to set the bytecode for
     * @param bytecode  the runtime bytecode to set for the account
     */
    void setAccountDelegationTarget(@NonNull AccountID accountID, @NonNull Bytes bytecode);

    Optional<Bytes> getAccountDelegationTarget(@NonNull AccountID accountID);
}
