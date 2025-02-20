// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes;
import com.hederahashgraph.api.proto.java.Query;
import java.util.List;

public class TokenGetNftInfosUsage extends QueryUsage {
    static final long INT_SIZE_AS_LONG = INT_SIZE;

    public TokenGetNftInfosUsage(final Query query) {
        super(query.getTokenGetNftInfos().getHeader().getResponseType());
        addTb(BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE_AS_LONG);
    }

    public static TokenGetNftInfosUsage newEstimate(final Query query) {
        return new TokenGetNftInfosUsage(query);
    }

    public TokenGetNftInfosUsage givenMetadata(final List<ByteString> metadata) {
        int additionalRb = 0;
        for (final ByteString m : metadata) {
            additionalRb += m.size();
        }
        addRb(additionalRb);
        addRb(NftEntitySizes.NFT_ENTITY_SIZES.fixedBytesInNftRepr() * metadata.size());

        return this;
    }
}
