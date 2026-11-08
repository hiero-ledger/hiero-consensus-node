// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the state-installation guard against malformed and unsupported rotation evidence. */
class QbftEpochProgressTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvidence")
    void cannotInstallAnEpochWithoutValidForwardEvidence(
            final String name, final byte[] payload, final byte[] successor, final String reason) {
        assertThatThrownBy(() -> BesuQbftVerifier.validateEpochProgress(payload, new byte[0], successor, 30_000))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining(reason);
    }

    static Stream<Arguments> invalidEvidence() {
        final byte[] firstEpoch = {1};
        final var validHeader = header(Rlp.encodeUint(30_000), 15);
        return Stream.of(
                Arguments.of("non-list bundle", Rlp.encodeBytes(new byte[0]), firstEpoch, "5 or 7 fields"),
                Arguments.of("incomplete bundle", Rlp.encodeList(List.of()), firstEpoch, "5 or 7 fields"),
                Arguments.of(
                        "non-list epoch evidence",
                        payload(Rlp.encodeBytes(new byte[0]), 5),
                        firstEpoch,
                        "epochBlockHeaders must be a list"),
                Arguments.of(
                        "claimed successor without evidence",
                        payload(Rlp.encodeList(List.of()), 5),
                        firstEpoch,
                        "successor epoch returned without an epoch header"),
                Arguments.of(
                        "non-header evidence",
                        rotation(Rlp.encodeBytes(new byte[] {1})),
                        firstEpoch,
                        "block-header RLP list"),
                Arguments.of(
                        "truncated header",
                        rotation(header(Rlp.encodeUint(30_000), 14)),
                        firstEpoch,
                        "block-header RLP list"),
                Arguments.of(
                        "unsupported header shape",
                        rotation(header(Rlp.encodeUint(30_000), 24)),
                        firstEpoch,
                        "block-header RLP list"),
                Arguments.of(
                        "genesis is not a successor",
                        rotation(header(Rlp.encodeBytes(new byte[0]), 15)),
                        firstEpoch,
                        "positive epoch boundary"),
                Arguments.of(
                        "non-boundary header",
                        rotation(header(Rlp.encodeUint(29_999), 15)),
                        firstEpoch,
                        "positive epoch boundary"),
                Arguments.of(
                        "oversized block number",
                        rotation(header(Rlp.encodeBytes(new byte[9]), 15)),
                        firstEpoch,
                        "number exceeds 8 bytes"),
                Arguments.of(
                        "unsigned number exceeds supported range",
                        rotation(header(Rlp.encodeBytes(new byte[] {(byte) 0x80, 0, 0, 0, 0, 0, 0, 0}), 15)),
                        firstEpoch,
                        "number exceeds supported range"),
                Arguments.of("missing successor", rotation(validHeader), new byte[0], "successor epoch does not match"),
                Arguments.of(
                        "successor differs from evidence",
                        rotation(validHeader),
                        new byte[] {2},
                        "successor epoch does not match"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsUnsupportedEpochLengthBeforeInstallingState(final long epochLength) {
        assertThatThrownBy(() -> BesuQbftVerifier.validateEpochProgress(
                        rotation(header(Rlp.encodeUint(30_000), 15)), new byte[0], new byte[] {1}, epochLength))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("epochLength must be positive");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 7})
    void ordinaryBundlesDoNotNeedRotationEvidence(final int fields) {
        assertThatCode(() -> BesuQbftVerifier.validateEpochProgress(
                        payload(Rlp.encodeList(List.of()), fields), new byte[] {5}, new byte[0], 30_000))
                .doesNotThrowAnyException();
    }

    private static byte[] rotation(final byte[] header) {
        return payload(Rlp.encodeList(List.of(header)), 5);
    }

    private static byte[] payload(final byte[] epochs, final int fields) {
        final var values = new ArrayList<byte[]>();
        for (int i = 0; i < fields; i++) {
            values.add(i == 1 ? epochs : Rlp.encodeList(List.of()));
        }
        return Rlp.encodeList(values);
    }

    private static byte[] header(final byte[] encodedBlockNumber, final int fields) {
        final var values = new ArrayList<byte[]>();
        for (int i = 0; i < fields; i++) {
            // Ethereum-compatible headers carry the block number at index 8.
            values.add(i == 8 ? encodedBlockNumber : Rlp.encodeBytes(new byte[0]));
        }
        return Rlp.encodeList(values);
    }
}
