// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.TokenGetNftInfosQuery;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenGetNftInfosUsageTest {
    private TokenGetNftInfosUsage subject;
    private TokenID id;
    private List<ByteString> metadata;

    @BeforeEach
    void setup() {
        metadata = List.of(ByteString.copyFromUtf8("some metadata"));
        id = TokenID.newBuilder().setShardNum(0).setRealmNum(0).setTokenNum(1).build();
        subject = TokenGetNftInfosUsage.newEstimate(tokenNftInfosQuery());
    }

    @Test
    void assessesEverything() {
        // given:
        subject.givenMetadata(metadata);

        // when:
        final var usage = subject.get();
        int additionalRb = 0;
        for (final ByteString m : metadata) {
            additionalRb += m.size();
        }
        final var expectedBytes =
                BASIC_QUERY_RES_HEADER + NFT_ENTITY_SIZES.fixedBytesInNftRepr() * metadata.size() + additionalRb;

        // then:
        final var node = usage.getNodedata();
        assertEquals(FeeBuilder.BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE, node.getBpt());
        assertEquals(expectedBytes, node.getBpr());
    }

    private Query tokenNftInfosQuery() {
        final var op = TokenGetNftInfosQuery.newBuilder().setTokenID(id).build();
        return Query.newBuilder().setTokenGetNftInfos(op).build();
    }
}
