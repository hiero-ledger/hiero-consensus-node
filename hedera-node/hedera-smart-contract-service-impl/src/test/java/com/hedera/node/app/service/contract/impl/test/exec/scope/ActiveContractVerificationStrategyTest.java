// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_SECP256K1_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActiveContractVerificationStrategyTest {
    private static final long ACTIVE_NUMBER = 1234L;
    private static final long SOME_OTHER_NUMBER = 2345L;
    private static final Bytes ACTIVE_ADDRESS = Bytes.fromHex("1234");
    private static final Bytes OTHER_ADDRESS = Bytes.fromHex("abcd");

    private static final Key ACTIVE_ID_KEY = Key.newBuilder()
            .contractID(ContractID.newBuilder().contractNum(ACTIVE_NUMBER))
            .build();
    private static final Key DELEGATABLE_ACTIVE_ID_KEY = Key.newBuilder()
            .delegatableContractId(ContractID.newBuilder().contractNum(ACTIVE_NUMBER))
            .build();
    private static final Key INACTIVE_ID_KEY = Key.newBuilder()
            .contractID(ContractID.newBuilder().contractNum(SOME_OTHER_NUMBER))
            .build();
    private static final Key DELEGATABLE_INACTIVE_ID_KEY = Key.newBuilder()
            .delegatableContractId(ContractID.newBuilder().contractNum(SOME_OTHER_NUMBER))
            .build();
    private static final Key ACTIVE_ADDRESS_KEY = Key.newBuilder()
            .contractID(ContractID.newBuilder().evmAddress(ACTIVE_ADDRESS))
            .build();
    private static final Key DELEGATABLE_ACTIVE_ADDRESS_KEY = Key.newBuilder()
            .delegatableContractId(ContractID.newBuilder().evmAddress(ACTIVE_ADDRESS))
            .build();
    private static final Key INACTIVE_ADDRESS_KEY = Key.newBuilder()
            .contractID(ContractID.newBuilder().evmAddress(OTHER_ADDRESS))
            .build();
    private static final Key DELEGATABLE_INACTIVE_ADDRESS_KEY = Key.newBuilder()
            .delegatableContractId(ContractID.newBuilder().evmAddress(OTHER_ADDRESS))
            .build();
    private static final Key CRYPTO_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("1234567812345678123456781234567812345678123456781234567812345678"))
            .build();
    private static final ContractID contractID =
            ContractID.newBuilder().contractNum(ACTIVE_NUMBER).build();

    @Mock
    private HandleContext context;

    @Test
    void validatesKeysAsExpectedWhenDelegatePermissionNotRequiredAndUsingTopLevelSigs() {
        final var subject =
                new ActiveContractVerificationStrategy(contractID, ACTIVE_ADDRESS, false, UseTopLevelSigs.YES);

        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ID_KEY));
        assertEquals(
                VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ADDRESS_KEY));
        assertEquals(
                VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION,
                subject.decideForPrimitive(CRYPTO_KEY));
    }

    @Test
    void validatesKeysAsExpectedWhenDelegatePermissionRequiredAndUsingTopLevelSigs() {
        final var subject =
                new ActiveContractVerificationStrategy(contractID, ACTIVE_ADDRESS, true, UseTopLevelSigs.YES);

        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ID_KEY));
        assertEquals(
                VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ADDRESS_KEY));
        assertEquals(
                VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION,
                subject.decideForPrimitive(CRYPTO_KEY));
    }

    @Test
    void validatesKeysAsExpectedWhenDelegatePermissionRequiredAndNotUsingTopLevelSigs() {
        final var subject =
                new ActiveContractVerificationStrategy(contractID, ACTIVE_ADDRESS, true, UseTopLevelSigs.NO);

        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(INACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ID_KEY));
        assertEquals(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(DELEGATABLE_ACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ID_KEY));
        assertEquals(
                VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(DELEGATABLE_INACTIVE_ADDRESS_KEY));
        assertEquals(VerificationStrategy.Decision.INVALID, subject.decideForPrimitive(CRYPTO_KEY));
    }

    @Test
    void signatureTestApprovesEthSenderKeyWhenDelegating() {
        final var subject = mock(VerificationStrategy.class);
        doCallRealMethod().when(subject).asPrimitiveSignatureTestIn(context, A_SECP256K1_KEY);
        given(subject.decideForPrimitive(A_SECP256K1_KEY))
                .willReturn(VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);

        final var test = subject.asPrimitiveSignatureTestIn(context, A_SECP256K1_KEY);
        assertTrue(test.test(A_SECP256K1_KEY));
    }

    @Test
    void signatureTestUsesContextVerificationWhenNotEthSenderKey() {
        final var keyVerifier = mock(KeyVerifier.class);
        final var verification = mock(SignatureVerification.class);
        final var subject = mock(VerificationStrategy.class);
        doCallRealMethod().when(subject).asPrimitiveSignatureTestIn(context, null);
        given(verification.passed()).willReturn(true);
        given(context.keyVerifier()).willReturn(keyVerifier);
        given(keyVerifier.verificationFor(B_SECP256K1_KEY)).willReturn(verification);
        given(subject.decideForPrimitive(B_SECP256K1_KEY))
                .willReturn(VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);

        final var test = subject.asPrimitiveSignatureTestIn(context, null);
        assertTrue(test.test(B_SECP256K1_KEY));
    }
}
