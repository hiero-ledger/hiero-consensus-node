// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.KECCAK_256_HASH;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.RAW;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus.INVALID;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus.ONLY_IF_CRYPTO_SIG_VALID;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.SimpleKeyStatus.VALID;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppSignatureVerifierTest {
    private static final Key ED_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("0101010101010101010101010101010101010101010101010101010101010101"))
            .build();
    private static final Key EC_KEY = Key.newBuilder()
            .ecdsaSecp256k1(Bytes.fromHex("020202020202020202020202020202020202020202020202020202020202020202"))
            .build();
    private static final Key CID_KEY = Key.newBuilder()
            .contractID(ContractID.newBuilder().contractNum(666L).build())
            .build();
    private static final Key TEST_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder().threshold(1).keys(new KeyList(List.of(ED_KEY, EC_KEY, CID_KEY))))
            .build();

    @Mock
    private SignatureExpander signatureExpander;

    @Mock
    private com.hedera.node.app.signature.SignatureVerifier signatureVerifier;

    @Mock
    private SignatureVerification verification;

    @Mock
    private SignatureVerificationFuture future;

    private AppSignatureVerifier subject;

    @BeforeEach
    void setUp() {
        subject = new AppSignatureVerifier(
                DEFAULT_CONFIG.getConfigData(HederaConfig.class), signatureExpander, signatureVerifier);
    }

    @Nested
    @DisplayName("with one expanded ED25519 signature pair")
    class WithOneExpandedEd25519SignaturePair {
        private final Set<ExpandedSignaturePair> expandedPairs =
                Set.of(new ExpandedSignaturePair(ED_KEY, ED_KEY.ed25519OrThrow(), null, SignaturePair.DEFAULT));

        @BeforeEach
        void setUp() {
            willAnswer(invocationOnMock -> {
                        final Set<ExpandedSignaturePair> pairs = invocationOnMock.getArgument(2);
                        pairs.addAll(expandedPairs);
                        return null;
                    })
                    .given(signatureExpander)
                    .expand(eq(TEST_KEY), eq(SignatureMap.DEFAULT.sigPair()), any(Set.class));
        }

        @Test
        void usesDefaultKeyVerifierWithNoOverride() throws ExecutionException, InterruptedException, TimeoutException {
            final var pretendKeccak256Hash = Bytes.wrap(new byte[32]);
            given(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).willReturn(verification);
            given(signatureVerifier.verify(pretendKeccak256Hash, expandedPairs, KECCAK_256_HASH))
                    .willReturn(Map.of(ED_KEY, future));

            assertThat(subject.verifySignature(
                            TEST_KEY, pretendKeccak256Hash, KECCAK_256_HASH, SignatureMap.DEFAULT, null))
                    .isTrue();
            // The default verifier counts the number of failed verifications in a key list, not the number passed
            verify(verification).failed();
        }

        @Test
        void usesDefaultKeyVerifierWithValidOverride()
                throws ExecutionException, InterruptedException, TimeoutException {
            given(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).willReturn(verification);
            given(signatureVerifier.verify(Bytes.EMPTY, expandedPairs, RAW)).willReturn(Map.of(ED_KEY, future));

            assertThat(subject.verifySignature(
                            TEST_KEY,
                            Bytes.EMPTY,
                            RAW,
                            SignatureMap.DEFAULT,
                            key -> key == CID_KEY ? VALID : ONLY_IF_CRYPTO_SIG_VALID))
                    .isTrue();
            // Here we are directly using the crypto verification passed() method ourselves in the callback
            verify(verification).passed();
        }

        @Test
        void usesDefaultKeyVerifierWithInvalidOverride() {
            given(signatureVerifier.verify(Bytes.EMPTY, expandedPairs, RAW)).willReturn(Map.of(ED_KEY, future));

            assertThat(subject.verifySignature(TEST_KEY, Bytes.EMPTY, RAW, SignatureMap.DEFAULT, key -> INVALID))
                    .isFalse();
        }
    }

    @Test
    void countsKeysAsExpected() {
        final var structure = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(2)
                        .keys(KeyList.newBuilder()
                                .keys(
                                        TEST_KEY,
                                        TEST_KEY,
                                        Key.newBuilder()
                                                .keyList(KeyList.newBuilder()
                                                        .keys(ED_KEY, CID_KEY, TEST_KEY)
                                                        .build())
                                                .build())
                                .build())
                        .build())
                .build();
        final var expectedCount = new com.hedera.node.app.spi.signatures.SignatureVerifier.KeyCounts(4, 3);
        final var actualCount = subject.countSimpleKeys(structure);
        assertThat(actualCount).isEqualTo(expectedCount);
    }

    @Test
    void throwsIaeForWrongLengthKeccakHash() {
        assertThatThrownBy(() -> subject.verifySignature(
                        TEST_KEY, Bytes.fromHex("deadbeef"), KECCAK_256_HASH, SignatureMap.DEFAULT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message type KECCAK_256_HASH must be 32 bytes long, got 'deadbeef'");
    }
}
