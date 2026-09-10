// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.sendmessage;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageTranslator.SEND_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.clpr.ClprServiceConstants;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SendMessageTranslatorTest extends CallAttemptTestBase {

    @Mock
    private ContractMetrics contractMetrics;

    private SendMessageTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SendMessageTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    @DisplayName("should match sendMessage selector")
    void matchesSendMessageSelector() {
        final var attempt = createClprCallAttempt(SEND_MESSAGE);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    @DisplayName("should not match unrelated selector")
    void doesNotMatchUnrelatedSelector() {
        final var attempt = createClprCallAttempt(BurnTranslator.BURN_TOKEN_V2);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    @DisplayName("should decode ABI arguments and create SendMessageCall")
    void decodesAbiAndCreatesCall() {
        final var channelId = new byte[32];
        channelId[0] = 42;
        final var connectorId = new byte[32];
        connectorId[0] = (byte) 0xCA;
        connectorId[1] = (byte) 0xFE;
        final var targetApp = new byte[] {10, 20, 30};
        final var messageData = new byte[] {1, 2, 3, 4, 5};

        final var tuple = Tuple.of(channelId, connectorId, targetApp, messageData);
        final var inputBytes = Bytes.wrap(SEND_MESSAGE.encodeCall(tuple).array());

        final var attempt = createClprCallAttemptWithInput(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.senderId()).willReturn(AccountID.DEFAULT);
        given(attempt.senderAddress()).willReturn(Address.ZERO);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(SendMessageCall.class);
    }

    private ClprCallAttempt createClprCallAttempt(
            @SuppressWarnings("unused") // we only need the selector
                    final com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod method) {
        return new ClprCallAttempt(
                Bytes.wrap(method.selector()),
                new CallAttemptOptions<>(
                        ClprServiceConstants.CLPR_CONTRACT_ID,
                        com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS,
                        Address.fromHexString(ClprServiceConstants.CLPR_EVM_ADDRESS),
                        com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS,
                        false,
                        mockEnhancement(),
                        com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        java.util.List.of(subject),
                        systemContractMethodRegistry,
                        false));
    }

    private ClprCallAttempt createClprCallAttemptWithInput(final Bytes input) {
        // For the callFrom test, we need a mock since we call attempt.senderId() etc.
        final var attempt = org.mockito.Mockito.mock(ClprCallAttempt.class);
        given(attempt.inputBytes()).willReturn(input.toArrayUnsafe());
        return attempt;
    }
}
