// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @BeforeEach
    void setUp() {
        subject =
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics);
    }

    @ParameterizedTest
    @CsvSource({ABI_ID_TRANSFER_TOKEN + ", true", ABI_ID_CRYPTO_TRANSFER_V2 + ", false"})
    void dispatchPayerMatchesAccountAuthority(final String selector, final boolean supportsQualifiedDelegate)
            throws ReflectiveOperationException {
        given(classicTransfersDecoder.checkForFailureStatus(any())).willReturn(null);
        given(addressIdConverter.convertSender(EIP_1014_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        given(addressIdConverter.convertSender(NON_SYSTEM_LONG_ZERO_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        given(verificationStrategies.activatingOnlyContractKeysFor(
                        NON_SYSTEM_LONG_ZERO_ADDRESS, true, nativeOperations))
                .willReturn(new AccountAuthorizingVerificationStrategy(A_NEW_ACCOUNT_ID, strategy));

        final var call = subject.callFrom(createHtsCallAttempt(selector, List.of(subject)));

        final var expectedPayer = supportsQualifiedDelegate ? A_NEW_ACCOUNT_ID : B_NEW_ACCOUNT_ID;
        final var payerField = ClassicTransfersCall.class.getDeclaredField("senderId");
        payerField.setAccessible(true);
        assertEquals(expectedPayer, payerField.get(call));
        final var strategyField = ClassicTransfersCall.class.getDeclaredField("verificationStrategy");
        strategyField.setAccessible(true);
        assertEquals(new AccountAuthorizingVerificationStrategy(expectedPayer, strategy), strategyField.get(call));
    }
}
