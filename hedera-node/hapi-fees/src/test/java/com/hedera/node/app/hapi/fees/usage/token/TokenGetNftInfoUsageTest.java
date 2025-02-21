// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.TokenGetNftInfoQuery;
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
        final var node = usage.getNodedata();

        assertEquals(FeeBuilder.BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + LONG_SIZE, node.getBpt());
        assertEquals(expectedBytes, node.getBpr());
    }

    private Query query() {
        final var op = TokenGetNftInfoQuery.newBuilder().setNftID(id).build();
        return Query.newBuilder().setTokenGetNftInfo(op).build();
    }
}
