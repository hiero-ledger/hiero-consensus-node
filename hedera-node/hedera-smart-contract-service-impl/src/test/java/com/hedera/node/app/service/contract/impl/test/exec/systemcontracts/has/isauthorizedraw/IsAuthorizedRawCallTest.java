// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorizedraw;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall.EC_SIGNATURE_MAX_LENGTH;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall.EC_SIGNATURE_MIN_LENGTH;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall.ED_SIGNATURE_LENGTH;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT_NUM;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.messageHash;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall.SignatureType;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

class IsAuthorizedRawCallTest extends CallTestBase {
    private IsAuthorizedRawCall subject;

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private SignatureVerifier signatureVerifier;

    private final CustomGasCalculator customGasCalculator = new CustomGasCalculator();

    @BeforeEach
    void setup() {
        lenient().when(attempt.systemContractGasCalculator()).thenReturn(gasCalculator);
        lenient().when(attempt.enhancement()).thenReturn(mockEnhancement());
        lenient().when(attempt.signatureVerifier()).thenReturn(signatureVerifier);
        lenient().when(frame.getRemainingGas()).thenReturn(10_000_000L);
    }

    @Test
    void sanityCheckSignatureLengths() {
        // Sadly, though AssertJ has `.isBetween` it does _not_ have `.isNotBetween`, or a general
        // way to negate assertions ...

        final var tooShortForEC = ED_SIGNATURE_LENGTH < EC_SIGNATURE_MIN_LENGTH;
        final var tooLongForEC = ED_SIGNATURE_LENGTH > EC_SIGNATURE_MAX_LENGTH;
        assertTrue(tooShortForEC || tooLongForEC);
    }

    @Test
    void revertsWithNoAccountAtAddress() {
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), any())).willReturn(MISSING_ENTITY_NUMBER);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        subject = getSubject(APPROVED_HEADLONG_ADDRESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ACCOUNT_ID), result.getOutput());
    }

    @Test
    void revertsWhenEcdsaIsNotEvmAddress() {
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(OWNER_ACCOUNT);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        subject = getSubject(asHeadlongAddress(OWNER_ACCOUNT_NUM));

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ACCOUNT_ID), result.getOutput());
    }

    @Test
    void notValidAccountIfNegative() {
        final var result = getSubject(mock(Address.class)).isValidAccount(-25L, mock(SignatureType.class));
        assertFalse(result);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 10L, 100L, 1_000_000_000_000L, Long.MAX_VALUE})
    void anyNonNegativeAccountValidIfED(final long account) {
        final var result = getSubject(mock(Address.class)).isValidAccount(account, SignatureType.ED);
        assertTrue(result);
    }

    @Test
    void validateEdSignatureSucceedsForValidSignature() {
        final var account = mock(Account.class);
        final var key = mock(Key.class);

        subject = getSubject(mock(Address.class));

        given(account.key()).willReturn(key);
        given(key.hasEd25519()).willReturn(true);
        given(key.ed25519()).willReturn(Bytes.wrap(new byte[] {1, 2, 3, 4}));
        given(signatureVerifier.verifySignature(
                        eq(key),
                        eq(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash)),
                        eq(SignatureVerifier.MessageType.KECCAK_256_HASH),
                        any(),
                        any()))
                .willReturn(true);

        final var result = subject.validateEdSignature(account, key);

        assertTrue(result, "Expected ED25519 signature validation to succeed");
    }

    @Test
    void validateEdSignatureFailsForInvalidSignature() {
        final var account = mock(Account.class);
        final var key = mock(Key.class);

        subject = getSubject(mock(Address.class));

        given(account.key()).willReturn(key);
        given(key.hasEd25519()).willReturn(true);
        given(key.ed25519()).willReturn(Bytes.wrap(new byte[] {1, 2, 3, 4}));

        given(signatureVerifier.verifySignature(
                        eq(key),
                        eq(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash)),
                        eq(SignatureVerifier.MessageType.KECCAK_256_HASH),
                        any(),
                        any()))
                .willReturn(false);

        final var result = subject.validateEdSignature(account, key);

        assertFalse(result, "Expected ED25519 signature validation to fail");
    }

    @Test
    void validateEdSignatureThrowsExceptionForMissingKey() {
        final var account = mock(Account.class);

        assertThrows(NullPointerException.class, () -> subject.validateEdSignature(account, null));
    }

    @Test
    void validateEcSignatureSucceedsForValidSignature() {
        final var account = mock(Account.class);
        final var key = mock(Key.class);

        subject = getSubject(mock(Address.class));

        given(account.key()).willReturn(key);
        given(key.hasEcdsaSecp256k1()).willReturn(true);
        given(key.ecdsaSecp256k1()).willReturn(Bytes.wrap(new byte[] {1, 2, 3, 4}));

        given(signatureVerifier.verifySignature(
                        eq(key),
                        eq(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash)),
                        eq(SignatureVerifier.MessageType.KECCAK_256_HASH),
                        any(),
                        any()))
                .willReturn(true);

        final var result = subject.validateEcSignature(account);

        assertTrue(result, "Expected ECDSA signature validation to succeed");
    }

    @Test
    void validateEcSignatureFailsForInvalidSignature() {
        final var account = mock(Account.class);
        final var key = mock(Key.class);

        subject = getSubject(mock(Address.class));

        given(account.key()).willReturn(key);
        given(key.hasEcdsaSecp256k1()).willReturn(true);
        given(key.ecdsaSecp256k1()).willReturn(Bytes.wrap(new byte[] {1, 2, 3, 4}));

        given(signatureVerifier.verifySignature(
                        eq(key),
                        eq(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(messageHash)),
                        eq(SignatureVerifier.MessageType.KECCAK_256_HASH),
                        any(),
                        any()))
                .willReturn(false);

        final var result = subject.validateEcSignature(account);

        assertFalse(result, "Expected ECDSA signature validation to fail due to invalid signature");
    }

    @Test
    void validateEcSignatureThrowsExceptionForNullAccount() {
        subject = getSubject(mock(Address.class));

        assertThrows(NullPointerException.class, () -> subject.validateEcSignature(null));
    }

    @Test
    void validateEcSignatureThrowsExceptionForNullKey() {
        final var account = mock(Account.class);

        subject = getSubject(mock(Address.class));

        given(account.key()).willReturn(null);

        assertFalse(subject.validateEcSignature(account));
    }

    @NonNull
    IsAuthorizedRawCall getSubject(@NonNull final Address address) {
        return new IsAuthorizedRawCall(attempt, address, messageHash, signature, customGasCalculator);
    }

    @NonNull
    byte[] asBytes(final long n) {
        return BigInteger.valueOf(n).toByteArray();
    }
}
