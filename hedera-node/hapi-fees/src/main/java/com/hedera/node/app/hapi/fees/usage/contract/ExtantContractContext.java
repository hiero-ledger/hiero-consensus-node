// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.contract;

import com.hedera.node.app.hapi.fees.usage.contract.entities.ContractEntitySizes;
import com.hedera.node.app.hapi.fees.usage.crypto.ExtantCryptoContext;

public class ExtantContractContext {
    private final int currentNumKvPairs;
    private final ExtantCryptoContext currentCryptoContext;

    public ExtantContractContext(final int currentNumKvPairs, final ExtantCryptoContext currentCryptoContext) {
        this.currentNumKvPairs = currentNumKvPairs;
        this.currentCryptoContext = currentCryptoContext;
    }

    public long currentRb() {
        return ContractEntitySizes.CONTRACT_ENTITY_SIZES.fixedBytesInContractRepr()
                + currentCryptoContext.currentNonBaseRb();
    }

    public long currentNumKvPairs() {
        return currentNumKvPairs;
    }
}
