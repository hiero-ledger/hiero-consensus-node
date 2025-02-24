// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.token.TokenGetNftInfosQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenGetNftInfosUsageTest {
    private TokenGetNftInfosUsage subject;
    private TokenID id;
    private List<Bytes> metadata;

    @BeforeEach
    void setup() {
        metadata = List.of(Bytes.wrap("some metadata"));
        id = TokenID.newBuilder().shardNum(0).realmNum(0).tokenNum(1).build();
        subject = TokenGetNftInfosUsage.newEstimate(tokenNftInfosQuery());
    }

    @Test
    void assessesEverything() {
        // given:
        subject.givenMetadata(metadata);

        // when:
        final var usage = subject.get();
        long additionalRb = 0;
        for (final Bytes m : metadata) {
            additionalRb += m.length();
        }
        final var expectedBytes =
                BASIC_QUERY_RES_HEADER + NFT_ENTITY_SIZES.fixedBytesInNftRepr() * metadata.size() + additionalRb;

        // then:
        final var node = usage.nodedata();
        assertEquals(FeeBuilder.BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE, node.bpt());
        assertEquals(expectedBytes, node.bpr());
    }

    private Query tokenNftInfosQuery() {
        final var op = TokenGetNftInfosQuery.newBuilder().tokenID(id).build();
        return Query.newBuilder().tokenGetNftInfos(op).build();
    }
}
