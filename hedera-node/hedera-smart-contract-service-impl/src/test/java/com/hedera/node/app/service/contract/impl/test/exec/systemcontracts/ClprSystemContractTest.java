// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageTranslator.SEND_MESSAGE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClprSystemContractTest {
    @ParameterizedTest
    @CsvSource({
        "true,false,65796,true", "true,false,65797,false",
        "true,true,65796,true", "true,true,65797,false",
        "false,true,133120,true", "false,true,133121,false",
        "false,false,8192,false"
    })
    void inputIsBoundedBeforeTranslation(
            final boolean send, final boolean clprDispatch, final int length, final boolean reachesTranslator) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .withValue("jumboTransactions.maxTxnSize", 8192)
                .getOrCreateConfig();
        final var frame = mock(MessageFrame.class);
        final var factory = mock(ClprCallFactory.class);
        final var contract = new ClprSystemContract(mock(GasCalculator.class), factory, mock(ContractMetrics.class));
        final var selector = send ? SEND_MESSAGE.selector() : new byte[4];
        final var input = Bytes.wrap(java.util.Arrays.copyOf(selector, length));
        final var id = ContractID.newBuilder().contractNum(366).build();
        final var type = FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT;
        try (var utils = mockStatic(FrameUtils.class)) {
            utils.when(() -> FrameUtils.configOf(frame)).thenReturn(config);
            utils.when(() -> FrameUtils.hederaConfigOf(frame)).thenReturn(config.getConfigData(HederaConfig.class));
            utils.when(() -> FrameUtils.isClprDispatch(frame)).thenReturn(clprDispatch);
            utils.when(() -> FrameUtils.callTypeOf(frame, FrameUtils.EntityType.REGULAR_ACCOUNT))
                    .thenReturn(type);
            when(factory.createCallAttemptFrom(id, input, type, frame)).thenReturn(mock(ClprCallAttempt.class));
            contract.computeFully(id, input, frame);
            verify(factory, times(reachesTranslator ? 1 : 0)).createCallAttemptFrom(id, input, type, frame);
        }
    }
}
