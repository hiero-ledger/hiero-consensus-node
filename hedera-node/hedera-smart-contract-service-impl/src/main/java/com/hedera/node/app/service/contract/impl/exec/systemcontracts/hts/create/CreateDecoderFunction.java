// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenTransactionBodyWrapper;

/**
 * A decoder function used for decoding create calls to HTS system contract.
 */
@FunctionalInterface
public interface CreateDecoderFunction {

    /**
     * @param input the raw input of thе call to decode
     * @param senderID the account id of the sender account
     * @param nativeOps the native Hedera operations
     * @param converter the address id converter
     * @return the transaction body of the call
     */
    TokenTransactionBodyWrapper decode(
            byte[] input, AccountID senderID, HederaNativeOperations nativeOps, AddressIdConverter converter);
}
