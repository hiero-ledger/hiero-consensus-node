// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.token.TokenGetNftInfoQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes;

public class TokenGetNftInfoUsage extends QueryUsage {
    public TokenGetNftInfoUsage(final Query query) {
        super(query.tokenGetNftInfoOrElse(TokenGetNftInfoQuery.DEFAULT)
                .headerOrElse(QueryHeader.DEFAULT)
                .responseType());
        addTb(BASIC_ENTITY_ID_SIZE);
        addTb(LONG_SIZE);
        addRb(NftEntitySizes.NFT_ENTITY_SIZES.fixedBytesInNftRepr());
    }

    public static TokenGetNftInfoUsage newEstimate(final Query query) {
        return new TokenGetNftInfoUsage(query);
    }

    public TokenGetNftInfoUsage givenMetadata(final String memo) {
        addRb(memo.length());
        return this;
    }
}
