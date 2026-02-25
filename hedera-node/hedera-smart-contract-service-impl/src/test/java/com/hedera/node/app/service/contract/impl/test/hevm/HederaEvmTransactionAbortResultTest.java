// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.WEI_NETWORK_GAS_PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaEvmTransactionAbortResultTest {
    @Mock
    private RootProxyWorldUpdater rootProxyWorldUpdater;

    @Test
    void translatesResourceExhaustionResultForContractStorageExceeded() {
        final var subject = HederaEvmTransactionResult.resourceExhaustionFrom(
                SENDER_ID,
                GAS_LIMIT / 2,
                WEI_NETWORK_GAS_PRICE.toLong(),
                ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED);
        assertEquals(ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED, subject.finalStatus());
        final var protoResult = subject.asProtoResultOf(null, rootProxyWorldUpdater, null);
        final var traditionalMessage = Bytes.wrap(
                        MAX_CONTRACT_STORAGE_EXCEEDED.protoName().getBytes(StandardCharsets.UTF_8))
                .toString();
        assertEquals(traditionalMessage, protoResult.errorMessage());
    }

    @Test
    void translatesResourceExhaustionResultForTotalStorageExceeded() {
        final var subject = HederaEvmTransactionResult.resourceExhaustionFrom(
                SENDER_ID, GAS_LIMIT / 2, WEI_NETWORK_GAS_PRICE.toLong(), MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
        assertEquals(MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED, subject.finalStatus());
        final var protoResult = subject.asProtoResultOf(null, rootProxyWorldUpdater, null);
        final var traditionalMessage = Bytes.wrap(
                        MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED.protoName().getBytes(StandardCharsets.UTF_8))
                .toString();
        assertEquals(traditionalMessage, protoResult.errorMessage());
    }
}
