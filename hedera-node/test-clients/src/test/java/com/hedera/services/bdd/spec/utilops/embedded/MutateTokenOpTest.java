package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.TokenID;
import org.junit.jupiter.api.Test;

class MutateTokenOpTest {

    @Test
    void tokenIdFromProtoToPbj() {
        final var protoTokenId =
                com.hederahashgraph.api.proto.java.TokenID.newBuilder().setTokenNum(42).build();
        assertDoesNotThrow(() -> {
            final var pbjToken = protoToPbj(protoTokenId, TokenID.class);
            assertEquals(42, pbjToken.tokenNum());
        });
    }

}
