// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.hedera.node.app.fixtures.signature.ExpandedSignaturePairFactory.ecdsaPair;
import static com.hedera.node.app.fixtures.signature.ExpandedSignaturePairFactory.ed25519Pair;
import static com.hedera.node.app.fixtures.signature.ExpandedSignaturePairFactory.hollowPair;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.KECCAK_256_HASH;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.RAW;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.TransactionSignature;
import org.hiero.base.crypto.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
final class SignatureVerifierImplTest extends AppTestBase implements Scenarios {
    /**
     * The "signed" bytes to test with. This really doesn't matter because we mock out the crypto engine, so it will
     * always return true (or false) as needed regardless of the actual bytes.
     */
    private Bytes signedBytes;
    /** The Crypto engine. We need this to be mocked, so we can change the behavior of the crypto engine. */
    @Mock
    private Cryptography cryptoEngine;
    /** Captures the args sent to the crypto engine. */
    @Captor
    ArgumentCaptor<TransactionSignature> sigsCaptor;
    /** The verifier under test. */
    private SignatureVerifierImpl verifier;

    @BeforeEach
    void setUp() {
        signedBytes = randomBytes(32);
        verifier = new SignatureVerifierImpl(cryptoEngine);
    }

    @Test
    @DisplayName("Null Args are not permitted")
    void failIfConstructorArgsAreNull() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new SignatureVerifierImpl(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null Args are not permitted")
    void failIfArgsAreNull() {
        final Set<ExpandedSignaturePair> signatures = emptySet();
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> verifier.verify(null, signatures)).isInstanceOf(NullPointerException.class);
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> verifier.verify(signedBytes, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Requires 32 bytes with KECCAK_256_HASH message type")
    void requires32BytesWithKeccak256HashMessageType() {
        final Set<ExpandedSignaturePair> signatures = emptySet();
        assertThatThrownBy(() -> verifier.verify(Bytes.wrap(new byte[31]), signatures, KECCAK_256_HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * If we are asked to verify the keys for an empty signature map, then we get back an empty map, since there was
     * nothing to verify.
     */
    @Test
    @DisplayName("If there are no signatures then the map is empty")
    void noSignatures() {
        final var result = verifier.verify(signedBytes, emptySet());
        assertThat(result).isEmpty();
    }

    /**
     * Each {@link ExpandedSignaturePair} sent to the {@link SignatureVerifier} will be included in the returned map.
     */
    @Test
    @DisplayName("All signatures are included in the result")
    void someSignatures() {
        // Given some different kinds of signatures, and a crypto engine that successfully finishes
        // every single signature check right away
        final var sigs = Set.of(
                ecdsaPair(ALICE.keyInfo().publicKey()),
                ed25519Pair(BOB.keyInfo().publicKey()),
                hollowPair(ERIN.keyInfo().publicKey(), ERIN.account()));

        //noinspection unchecked
        doAnswer((Answer<Void>) invocation -> {
                    final TransactionSignature signature = invocation.getArgument(0);
                    signature.setSignatureStatus(VerificationStatus.VALID);
                    return null;
                })
                .when(cryptoEngine)
                .verifySync(any(TransactionSignature.class));

        // When we verify them
        final var map = verifier.verify(signedBytes, sigs);

        // Then we find each of the keys are present
        assertThat(map).hasSize(3);

        var future = map.get(ALICE.keyInfo().publicKey());
        assertThat(future).isNotNull();
        assertThat(future)
                .succeedsWithin(1, TimeUnit.SECONDS)
                .extracting("passed")
                .isEqualTo(true);
    }

    @ParameterizedTest
    @CsvSource({"RAW", "KECCAK_256_HASH"})
    @DisplayName("Crypto Engine is given array with all the required data")
    void cryptoEngineIsGivenAllTheData(com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType messageType) {
        // Given some different kinds of signatures
        final var sigs = new LinkedHashSet<ExpandedSignaturePair>(); // for predictable iteration for the test
        sigs.add(ecdsaPair(ALICE.keyInfo().publicKey()));
        sigs.add(ed25519Pair(BOB.keyInfo().publicKey()));
        sigs.add(hollowPair(ERIN.keyInfo().publicKey(), ERIN.account()));

        // The signed bytes for ECDSA keys are keccak hashes
        final var signedBytesArray = new byte[(int) signedBytes.length()];
        signedBytes.getBytes(0, signedBytesArray, 0, signedBytesArray.length);
        final var keccakSignedBytes = Bytes.wrap(MiscCryptoUtils.keccak256DigestOf(signedBytesArray));

        // When we verify them
        verifier.verify(signedBytes, sigs, messageType);

        // Then we find the crypto engine was given an array with all the data
        verify(cryptoEngine, times(3)).verifySync(sigsCaptor.capture());
        final var txSigs = sigsCaptor.getAllValues();

        final var itr = sigs.iterator();
        for (int i = 0; i < 3; i++) {
            final var expandedSigPair = itr.next();
            final var txSig = txSigs.get(i);
            if (messageType == RAW) {
                assertThat(txSig.getMessage()
                                .matchesPrefix(i == 1 ? signedBytes : keccakSignedBytes)) // index 1 is ed25519
                        .isTrue();
            } else {
                // For a KECCAK_256_HASH message type, the signed bytes are always the given hash
                assertThat(txSig.getMessage().matchesPrefix(signedBytes)).isTrue();
            }

            assertThat(txSig.getSignature().matchesPrefix(expandedSigPair.signature()))
                    .isTrue();

            assertThat(txSig.getPublicKey().matchesPrefix(expandedSigPair.keyBytes()))
                    .isTrue();
        }
    }
}
