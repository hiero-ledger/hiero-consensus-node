// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.token.TokenGetNftInfoQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenGetNftInfoUsageTest {
    private final String memo = "Hope";
    private final NftID id = IdUtils.asNftID("0.0.75231", 1);

    private TokenGetNftInfoUsage subject;

    @BeforeEach
    void setup() {
        subject = TokenGetNftInfoUsage.newEstimate(query());
    }

    @Test
    void assessesEverything() {
        // given:
        subject.givenMetadata(memo);
        // and:
        final var expectedBytes = BASIC_QUERY_RES_HEADER + NFT_ENTITY_SIZES.fixedBytesInNftRepr() + memo.length();

        // when:
        final var usage = subject.get();

        // then:
        final var node = usage.nodedata();

        assertEquals(FeeBuilder.BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + LONG_SIZE, node.bpt());
        assertEquals(expectedBytes, node.bpr());
    }

    private Query query() {
        final var op = TokenGetNftInfoQuery.newBuilder().nftID(id).build();
        return Query.newBuilder().tokenGetNftInfo(op).build();
    }
}
