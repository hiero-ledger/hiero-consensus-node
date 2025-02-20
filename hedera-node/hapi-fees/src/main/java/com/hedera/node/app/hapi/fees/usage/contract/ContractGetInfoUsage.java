// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.contract;

import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.contract.entities.ContractEntitySizes;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import java.nio.charset.StandardCharsets;

public final class ContractGetInfoUsage extends QueryUsage {
    private ContractGetInfoUsage(final Query query) {
        super(query.getContractGetInfo().getHeader().getResponseType());
        addTb(BASIC_ENTITY_ID_SIZE);
        addRb(ContractEntitySizes.CONTRACT_ENTITY_SIZES.fixedBytesInContractRepr());
    }

    public static ContractGetInfoUsage newEstimate(final Query query) {
        return new ContractGetInfoUsage(query);
    }

    public ContractGetInfoUsage givenCurrentKey(final Key key) {
        addRb(getAccountKeyStorageSize(key));
        return this;
    }

    public ContractGetInfoUsage givenCurrentMemo(final String memo) {
        addRb(memo.getBytes(StandardCharsets.UTF_8).length);
        return this;
    }

    public ContractGetInfoUsage givenCurrentTokenAssocs(final int count) {
        addRb(count * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr());
        return this;
    }
}
