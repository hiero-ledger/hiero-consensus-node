// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorized;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.message;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static com.hedera.pbj.runtime.io.buffer.Bytes.wrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerifier.KeyCounts;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class IsAuthorizedCallTest extends CallTestBase {

    private IsAuthorizedCall subject;

    @Mock
    private HasCallAttempt mockAttempt;

    @Mock
    private SignatureVerifier mockSignatureVerifier;

    @Mock
    private Account mockAccount;

    @Mock
    private Key mockKey;

    @Mock
    private CustomGasCalculator mockCustomGasCalculator;

    @BeforeEach
    void setup() {
        given(mockAttempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(mockAttempt.enhancement()).willReturn(mockEnhancement());
        given(mockAttempt.signatureVerifier()).willReturn(mockSignatureVerifier);
        lenient().when(frame.getRemainingGas()).thenReturn(10_000_000L);
    }

    @Test
    void returnsErrorStatusForInvalidAddress() {
        // Not an account num alias, not an evm alias
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), any())).willReturn(MISSING_ENTITY_NUMBER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(HederaTestConfigBuilder.createConfig());
        subject = getSubject(APPROVED_HEADLONG_ADDRESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.COMPLETED_SUCCESS, result.getState());

        final var output = getOutput(result);
        assertEquals(output.rce(), INVALID_ACCOUNT_ID);
    }

    @Test
    void returnsErrorStatusForMissingKey() {

        try (final MockedStatic<ConversionUtils> mockConversionUtils =
                Mockito.mockStatic(ConversionUtils.class, InvocationOnMock::callRealMethod)) {
            mockConversionUtils
                    .when(() ->
                            ConversionUtils.accountNumberForEvmReference(APPROVED_HEADLONG_ADDRESS, nativeOperations))
                    .thenReturn(1001L);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
            given(nativeOperations.getAccount(entityIdFactory.newAccountId(1001L)))
                    .willReturn(mockAccount);
            given(mockAccount.key()).willReturn(null);

            subject = getSubject(APPROVED_HEADLONG_ADDRESS);

            final var result = subject.execute(frame).fullResult().result();

            assertEquals(State.COMPLETED_SUCCESS, result.getState());

            final var output = getOutput(result);
            assertEquals(output.rce(), INVALID_TRANSACTION_BODY);
        }
    }

    @Test
    void returnsErrorStatusForInvalidSignatureBlob() {

        try (final MockedStatic<ConversionUtils> mockConversionUtils =
                Mockito.mockStatic(ConversionUtils.class, InvocationOnMock::callRealMethod)) {
            mockConversionUtils
                    .when(() ->
                            ConversionUtils.accountNumberForEvmReference(APPROVED_HEADLONG_ADDRESS, nativeOperations))
                    .thenReturn(1001L);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
            given(nativeOperations.getAccount(entityIdFactory.newAccountId(1001L)))
                    .willReturn(mockAccount);
            given(mockAccount.key()).willReturn(mockKey);

            // Took a valid protobuf for some a message containing only a long string field and
            // chopped off the last handful of bytes: Therefore this protobuf doesn't deserialize.
            // (BTW: Check out https://protobufpal.com.)
            final var invalidProtobuf =
                    Base64.getDecoder().decode("CklUaGlzIGlzIGEgdGVzdCwgdGhpcyBpcyBhIHZlcnkgdmVyeS");

            subject = getSubject(APPROVED_HEADLONG_ADDRESS, invalidProtobuf);

            final var result = subject.execute(frame).fullResult().result();

            assertEquals(State.COMPLETED_SUCCESS, result.getState());

            final var output = getOutput(result);
            assertEquals(output.rce(), INVALID_TRANSACTION_BODY);
        }
    }

    @Test
    void fixSignaturesButNothingToFix() {
        final var sigMapIn = getSignatureMapWith(3, 0, 2, 4);
        final var subject = getSubject(APPROVED_HEADLONG_ADDRESS, encodeSignatureMap(sigMapIn));
        final var sigMapOut = subject.fixEcSignaturesInMap(sigMapIn);
        assertThat(sigMapOut.sigPair())
                .containsExactlyInAnyOrder(sigMapIn.sigPair().toArray(new SignaturePair[0]));
    }

    @Test
    void fixSignaturesWithSomeToFix() {
        final var sigMapIn = getSignatureMapWith(2, 2, 2, 2);
        final var subject = getSubject(APPROVED_HEADLONG_ADDRESS, encodeSignatureMap(sigMapIn));
        final var sigMapOut = subject.fixEcSignaturesInMap(sigMapIn);

        final var pairsIn = new HashSet<>(sigMapIn.sigPair());
        final var pairsOut = new HashSet<>(sigMapOut.sigPair());

        assertThat(pairsIn).hasSize(8);
        assertThat(pairsOut).hasSize(8);

        // Should be 2+2+2 = 6 that are identical between in and out
        final var intersection = new HashSet<>(pairsIn);
        intersection.retainAll(pairsOut);

        assertThat(intersection).hasSize(6);

        // Remaining two in each must be EC, 65 bytes long 'in', 64 bytes long 'out', and the 'out's are the truncated
        // 'ins'
        pairsIn.removeAll(intersection);
        pairsOut.removeAll(intersection);

        assertThat(pairsIn).allSatisfy(pair -> {
            assertThat(pair.signature().kind()).isEqualTo(SignatureOneOfType.ECDSA_SECP256K1);
            assertThat(pair.ecdsaSecp256k1OrElse(Bytes.EMPTY).length()).isEqualTo(65);
        });
        assertThat(pairsOut).allSatisfy(pair -> {
            assertThat(pair.signature().kind()).isEqualTo(SignatureOneOfType.ECDSA_SECP256K1);
            assertThat(pair.ecdsaSecp256k1OrElse(Bytes.EMPTY).length()).isEqualTo(64);
        });

        final var truncatedPairsInSigs = pairsIn.stream()
                .map(p -> p.ecdsaSecp256k1OrThrow().slice(0, 64))
                .toList();
        final var pairsOutSigs =
                pairsOut.stream().map(p -> p.ecdsaSecp256k1OrThrow()).toList();
        assertThat(pairsOutSigs).containsExactlyInAnyOrder(truncatedPairsInSigs.toArray(new Bytes[0]));
    }

    @Test
    void expectedGasCosts() {

        final int nECsigs = 3;
        final int nEDsigs = 5;

        final int nECkeys = 7;
        final int nEDkeys = 2;

        final long ecGasPrice = 10L;
        final long edGasPrice = 1000L;

        final var sigBlob = encodeSignatureMap(getSignatureMapWith(nECsigs, 0, nEDsigs, 0));

        given(mockCustomGasCalculator.getEcrecPrecompiledContractGasCost()).willReturn(ecGasPrice);
        given(mockCustomGasCalculator.getEdSignatureVerificationSystemContractGasCost())
                .willReturn(edGasPrice);

        final var actualGas = new AtomicLong();

        final var sut =
                new IsAuthorizedCall(
                        mockAttempt, APPROVED_HEADLONG_ADDRESS, message, sigBlob, mockCustomGasCalculator) {
                    @Override
                    protected boolean verifyMessage(
                            @NonNull final Key key,
                            @NonNull final Bytes message,
                            @NonNull final MessageType msgType,
                            @NonNull final SignatureMap signatureMap,
                            @NonNull final Function<Key, SimpleKeyStatus> keyHandlingHook) {
                        return true;
                    }

                    @Override
                    @NonNull
                    protected PricedResult encodedOutput(
                            final ResponseCodeEnum rce, final boolean authorized, final long gasRequirement) {
                        actualGas.addAndGet(gasRequirement);
                        return new PricedResult(null, 0, null, false);
                    }
                };

        try (final MockedStatic<ConversionUtils> mockConversionUtils =
                Mockito.mockStatic(ConversionUtils.class, InvocationOnMock::callRealMethod)) {
            mockConversionUtils
                    .when(() ->
                            ConversionUtils.accountNumberForEvmReference(APPROVED_HEADLONG_ADDRESS, nativeOperations))
                    .thenReturn(1001L);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
            given(nativeOperations.getAccount(entityIdFactory.newAccountId(1001L)))
                    .willReturn(mockAccount);
            given(mockAccount.key()).willReturn(mockKey);
            given(mockSignatureVerifier.countSimpleKeys(mockKey)).willReturn(new KeyCounts(nEDkeys, nECkeys));

            sut.execute(frame);
        }
        assertEquals(nECkeys * ecGasPrice + nEDkeys * edGasPrice, actualGas.get());
    }

    @NonNull
    IsAuthorizedCall getSubject(@NonNull final Address address) {
        return getSubject(address, signature);
    }

    @NonNull
    IsAuthorizedCall getSubject(@NonNull final Address address, @NonNull final byte[] signatureBlob) {
        return new IsAuthorizedCall(mockAttempt, address, message, signatureBlob, mockCustomGasCalculator);
    }

    /** Creates a signature map with known entries. They aren't cryptographically _correct_ entries -
     * i.e., the keys and signatures are nonsense - but they're _known_.
     */
    @NonNull
    SignatureMap getSignatureMapWith(
            final int numEcdsa64, final int numEcdsa65, final int numEd25519, final int numOther) {
        final List<SignaturePair> pairs = new ArrayList<>();
        int from = 0;
        for (var i = 0; i < numEcdsa64; i++)
            pairs.add(getSignaturePair(SignatureOneOfType.ECDSA_SECP256K1, wrap(getKnownBytes(64, from++))));
        for (var i = 0; i < numEcdsa65; i++)
            pairs.add(getSignaturePair(SignatureOneOfType.ECDSA_SECP256K1, wrap(getKnownBytes(65, from++))));
        for (var i = 0; i < numEd25519; i++)
            pairs.add(getSignaturePair(SignatureOneOfType.ED25519, wrap(getKnownBytes(64, from++))));
        for (var i = 0; i < numOther; i++)
            pairs.add(getSignaturePair(SignatureOneOfType.RSA_3072, wrap(getKnownBytes(10, from++))));

        final var rand = new Random(101010);
        Collections.shuffle(pairs, rand);

        return new SignatureMap(pairs);
    }

    @NonNull
    byte[] encodeSignatureMap(@NonNull final SignatureMap sigMap) {
        return SignatureMap.PROTOBUF.toBytes(sigMap).toByteArray();
    }

    /** Creates a signature pair out of thin air (but only ED, EC, CONTRACT, and "other" */
    @NonNull
    SignaturePair getSignaturePair(@NonNull final SignaturePair.SignatureOneOfType type, final Bytes bytes) {

        final var b = new SignaturePair.Builder().pubKeyPrefix(null);
        switch (type) {
            case ED25519:
                b.ed25519(bytes);
                break;
            case ECDSA_SECP256K1:
                b.ecdsaSecp256k1(bytes);
                break;
            case CONTRACT:
                b.contract(bytes);
                break;
            default:
                b.rsa3072(bytes);
                break;
        }
        return b.build();
    }

    record IAOutput(ResponseCodeEnum rce, boolean result) {}
    ;
    /** Translate the contract output byte array to the `(ResponseCode,boolean)` result it emitted */
    @NonNull
    IAOutput getOutput(@NonNull final PrecompileContractResult pcr) {
        final var tuple = IsAuthorizedTranslator.IS_AUTHORIZED
                .getOutputs()
                .decode(pcr.getOutput().toArray());
        final var rce = ResponseCodeEnum.fromProtobufOrdinal(Math.toIntExact((Long) tuple.get(0)));
        final var result = (Boolean) tuple.get(1);
        return new IAOutput(rce, result);
    }

    @NonNull
    byte[] getKnownBytes(final int length, final int from) {
        final var bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (from + i);
        return bytes;
    }
}
