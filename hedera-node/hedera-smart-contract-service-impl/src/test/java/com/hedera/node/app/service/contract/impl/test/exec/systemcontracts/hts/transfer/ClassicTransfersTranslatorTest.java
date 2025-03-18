// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ClassicTransfersTranslatorTest extends CallAttemptTestBase {

    private static final String ABI_ID_TRANSFER_TOKEN = "eca36917";
    private static final String ABI_ID_CRYPTO_TRANSFER_V2 = "0e71804f";

    @Mock
    private ClassicTransfersDecoder classicTransfersDecoder;

    @Mock
    private VerificationStrategy strategy;

    @Mock
    private ContractMetrics contractMetrics;

    private ClassicTransfersTranslator subject;

    private List<CallTranslator<HtsCallAttempt>> callTranslators;

    @BeforeEach
    void setUp() {
        callTranslators = List.of(
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void returnsAttemptWithAuthorizingId() throws NoSuchFieldException, IllegalAccessException {
        given(classicTransfersDecoder.checkForFailureStatus(any())).willReturn(null);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertSender(NON_SYSTEM_LONG_ZERO_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(
                        NON_SYSTEM_LONG_ZERO_ADDRESS, true, nativeOperations))
                .willReturn(strategy);

        subject =
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics);
        final var call = subject.callFrom(createHtsCallAttempt(ABI_ID_TRANSFER_TOKEN, callTranslators));
        Field senderIdField = ClassicTransfersCall.class.getDeclaredField("senderId");
        senderIdField.setAccessible(true);
        AccountID senderID = (AccountID) senderIdField.get(call);
        assertEquals(A_NEW_ACCOUNT_ID, senderID);
    }

    @Test
    void returnsAttemptWithSenderId() throws NoSuchFieldException, IllegalAccessException {
        given(classicTransfersDecoder.checkForFailureStatus(any())).willReturn(null);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(
                        NON_SYSTEM_LONG_ZERO_ADDRESS, true, nativeOperations))
                .willReturn(strategy);

        subject =
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics);
        final var call = subject.callFrom(createHtsCallAttempt(ABI_ID_CRYPTO_TRANSFER_V2, callTranslators));
        Field senderIdField = ClassicTransfersCall.class.getDeclaredField("senderId");
        senderIdField.setAccessible(true);
        AccountID senderID = (AccountID) senderIdField.get(call);
        assertEquals(B_NEW_ACCOUNT_ID, senderID);
    }
}
