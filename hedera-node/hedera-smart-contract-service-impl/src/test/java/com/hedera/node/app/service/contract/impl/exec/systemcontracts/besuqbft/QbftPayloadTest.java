// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QbftPayloadTest {
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] STATE_ROOT = new byte[32];
    private static final byte[] EXTRA_DATA = new byte[56];
    private static final byte[] PROOF_NODE = new byte[] {1, 2, 3};
    private static final byte[] STORAGE_KEY = new byte[] {(byte) 0x80};
    private static final byte[] INNER_CONTENT = new byte[] {4, 5, 6};
    private static final ClprLedgerConfiguration CONFIG = ClprLedgerConfiguration.newBuilder()
            .chainId("1337")
            .initialTrustAnchor(Bytes.wrap(new byte[] {7, 8, 9}))
            .build();

    @ParameterizedTest
    @ValueSource(ints = {15, 16, 17, 18})
    void decodesBundleFieldsAndProofs(final int headerFields) {
        final var payload = QbftBundlePayload.decode(RLPEncoder.list(bundle(headerFields)));

        assertThat(payload.blockHeader().stateRoot()).isEqualTo(Bytes.wrap(STATE_ROOT));
        assertThat(payload.blockHeader().difficulty()).isEqualTo(BigInteger.valueOf(128));
        assertThat(payload.blockHeader().number()).isEqualTo(BigInteger.ZERO);
        assertThat(payload.blockHeader().extraData()).isEqualTo(Bytes.wrap(EXTRA_DATA));
        assertThat(payload.accountProof()).containsExactly(Bytes.wrap(PROOF_NODE));
        assertThat(payload.storageProof()).containsExactly(storageEntry());
        assertThat(payload.innerContentBytes()).isEqualTo(Bytes.wrap(INNER_CONTENT));
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 16, 17, 18})
    void decodesConfigFieldsAndProofs(final int headerFields) {
        final var payload = QbftLedgerConfigPayload.decode(RLPEncoder.list(config(headerFields)));

        assertThat(payload.genesisBlockHeader().difficulty()).isEqualTo(BigInteger.valueOf(128));
        assertThat(payload.currentBlockHeader().stateRoot()).isEqualTo(Bytes.wrap(STATE_ROOT));
        assertThat(payload.currentBlockHeader().extraData()).isEqualTo(Bytes.wrap(EXTRA_DATA));
        assertThat(payload.ledgerConfiguration()).isEqualTo(CONFIG);
        assertThat(payload.clprServiceAccountProof()).containsExactly(Bytes.wrap(PROOF_NODE));
        assertThat(payload.clprServiceStorageProofs()).containsExactly(storageEntry());
    }

    @Test
    void rejectsTrailingBytesAfterValidPayloads() {
        assertThatThrownBy(() -> QbftBundlePayload.decode(withTrailingByte(RLPEncoder.list(bundle(15)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
        assertThatThrownBy(() -> QbftLedgerConfigPayload.decode(withTrailingByte(RLPEncoder.list(config(15)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRlp")
    void rejectsMalformedRlp(final String description, final byte[] encoded) {
        assertThatThrownBy(() -> QbftBundlePayload.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QbftLedgerConfigPayload.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> malformedRlp() {
        return Stream.of(
                Arguments.of("empty input", EMPTY),
                Arguments.of("top-level string", new byte[] {(byte) 0x80}),
                Arguments.of("truncated short list", new byte[] {(byte) 0xc1}),
                Arguments.of("truncated long list", new byte[] {(byte) 0xf8, 0x38}),
                Arguments.of("child exceeds parent list", new byte[] {(byte) 0xc1, (byte) 0x81}),
                Arguments.of("non-canonical long list", new byte[] {(byte) 0xf8, 0x01, (byte) 0x80}));
    }

    @Test
    void rejectsNonCanonicalStringInsideOtherwiseValidBundle() {
        // Four fields: a 15-field header, empty account/storage proofs, and 0x01 encoded as 0x8101.
        final var encoded = HexFormat.of().parseHex("d4cf808080808080808080808080808080c0c08101");

        assertThatThrownBy(() -> QbftBundlePayload.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid rlp for single byte");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloadFields")
    void rejectsWrongFieldTypesAndCounts(final String description, final Object[] bundle, final Object[] config) {
        assertThatThrownBy(() -> QbftBundlePayload.decode(RLPEncoder.list(bundle)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QbftLedgerConfigPayload.decode(RLPEncoder.list(config)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidPayloadFields() {
        return Stream.of(
                Arguments.of("wrong top-level count", new Object[0], new Object[0]),
                Arguments.of("header is a string", replace(bundle(15), 0, EMPTY), replace(config(15), 0, EMPTY)),
                Arguments.of("header is too short", bundle(14), config(14)),
                Arguments.of("header is too long", bundle(19), config(19)),
                Arguments.of(
                        "header field is a list", replaceHeaderField(bundle(15), 7), replaceHeaderField(config(15), 7)),
                Arguments.of(
                        "optional header field is a list",
                        replaceHeaderField(bundle(16), 15),
                        replaceHeaderField(config(16), 15)),
                Arguments.of(
                        "proof node is a list",
                        replace(bundle(15), 1, List.of(List.of())),
                        replace(config(15), 3, List.of(List.of()))),
                Arguments.of(
                        "storage entry has wrong count",
                        replace(bundle(15), 2, List.of(List.of(STORAGE_KEY))),
                        replace(config(15), 4, List.of(List.of(STORAGE_KEY)))),
                Arguments.of(
                        "storage key is a list",
                        replace(bundle(15), 2, List.of(List.of(List.of(), List.of()))),
                        replace(config(15), 4, List.of(List.of(List.of(), List.of())))),
                Arguments.of(
                        "inner content is a list",
                        replace(bundle(15), 3, List.of()),
                        replace(config(15), 2, List.of())));
    }

    private static Object[] bundle(final int headerFields) {
        return new Object[] {header(headerFields), List.of(PROOF_NODE), storageProof(), INNER_CONTENT};
    }

    private static Object[] config(final int headerFields) {
        return new Object[] {
            header(headerFields),
            header(headerFields),
            ClprLedgerConfiguration.PROTOBUF.toBytes(CONFIG).toByteArray(),
            List.of(PROOF_NODE),
            storageProof()
        };
    }

    private static List<Object> header(final int count) {
        final var fields = new ArrayList<Object>(Collections.nCopies(count, EMPTY));
        fields.set(3, STATE_ROOT);
        fields.set(7, new byte[] {(byte) 0x80});
        fields.set(12, EXTRA_DATA);
        return fields;
    }

    private static List<?> storageProof() {
        return List.of(List.of(STORAGE_KEY, List.of(PROOF_NODE)));
    }

    private static PayloadPieces.StorageProofEntry storageEntry() {
        return new PayloadPieces.StorageProofEntry(Bytes.wrap(STORAGE_KEY), List.of(Bytes.wrap(PROOF_NODE)));
    }

    private static Object[] replace(final Object[] fields, final int index, final Object value) {
        fields[index] = value;
        return fields;
    }

    private static Object[] replaceHeaderField(final Object[] fields, final int index) {
        final var header = new ArrayList<Object>((List<?>) fields[0]);
        header.set(index, List.of());
        return replace(fields, 0, header);
    }

    private static byte[] withTrailingByte(final byte[] encoded) {
        return Arrays.copyOf(encoded, encoded.length + 1);
    }
}
