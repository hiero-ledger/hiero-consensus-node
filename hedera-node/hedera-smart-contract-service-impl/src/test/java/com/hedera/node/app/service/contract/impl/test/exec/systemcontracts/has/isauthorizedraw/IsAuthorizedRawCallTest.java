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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall.SignatureType;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
        "0,27",
        "1,28",
        "27,27",
        "28,28",
        "45,27",
        "46,28",
        "1000,28",
        "1001,27",
        "10000000,28",
        "10000003,27",
        "18,"
    })
    void reverseVTest(final long fromV, final Byte expectedV) {
        subject = getSubject(APPROVED_HEADLONG_ADDRESS);

        final var r = asBytes(fromV);
        final var ecSig = new byte[64 + r.length];
        System.arraycopy(r, 0, ecSig, 64, r.length);
        final var v = subject.reverseV(ecSig);
        if (expectedV == null) assertTrue(v.isEmpty());
        else assertEquals(expectedV, v.get());
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
