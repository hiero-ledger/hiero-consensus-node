// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import java.nio.charset.StandardCharsets;

public final class CryptoGetInfoUsage extends QueryUsage {
    private CryptoGetInfoUsage(final Query query) {
        super(query.getCryptoGetInfo().getHeader().getResponseType());
        addTb(BASIC_ENTITY_ID_SIZE);
        addRb(CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr());
    }

    public static CryptoGetInfoUsage newEstimate(final Query query) {
        return new CryptoGetInfoUsage(query);
    }

    public CryptoGetInfoUsage givenCurrentKey(final Key key) {
        addRb(getAccountKeyStorageSize(key));
        return this;
    }

    public CryptoGetInfoUsage givenCurrentMemo(final String memo) {
        addRb(memo.getBytes(StandardCharsets.UTF_8).length);
        return this;
    }

    public CryptoGetInfoUsage givenCurrentTokenAssocs(final int count) {
        addRb(count * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr());
        return this;
    }

    public CryptoGetInfoUsage givenCurrentlyUsingProxy() {
        addRb(BASIC_ENTITY_ID_SIZE);
        return this;
    }
}
