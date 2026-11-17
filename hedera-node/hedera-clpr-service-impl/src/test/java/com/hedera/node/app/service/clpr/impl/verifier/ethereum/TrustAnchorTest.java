// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.PbjTestUtils.appendUnknownField;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.EthereumPosCommitteeAnchor;
import com.hedera.hapi.node.state.clpr.EthereumPosTrustAnchor;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrustAnchorTest {

    private static final byte[] GENESIS_VALIDATORS_ROOT = deterministicBytes(32, 0x10);
    private static final byte[] FORK_VERSION = {0x08, 0x00, 0x00, 0x01};
    private static final byte[] CODE_HASH = deterministicBytes(32, 0x60);
    private static final long PERIOD = 0x0102L;

    private static final SyncCommittee CURRENT =
            new SyncCommittee(deterministicBytes(32, 0x20), deterministicBytes(48, 0x30));
    private static final SyncCommittee NEXT =
            new SyncCommittee(deterministicBytes(32, 0x40), deterministicBytes(48, 0x50));

    private static final TrustAnchor ANCHOR = new TrustAnchor(
            SyncCommitteePreset.MINIMAL, GENESIS_VALIDATORS_ROOT, FORK_VERSION, PERIOD, CURRENT, NEXT, CODE_HASH);

    @Test
    @DisplayName("given an anchor, then encode writes an EthereumPosTrustAnchor protobuf with every field")
    void givenAnchor_thenEncodeWritesTrustAnchorProtobuf() throws ParseException {
        final EthereumPosTrustAnchor encoded = EthereumPosTrustAnchor.PROTOBUF.parseStrict(
                Bytes.wrap(ANCHOR.encode()).toReadableSequentialData());

        assertThat(encoded.preset()).isEqualTo(2); // minimal
        assertThat(encoded.genesisValidatorsRoot().toByteArray()).isEqualTo(GENESIS_VALIDATORS_ROOT);
        assertThat(encoded.forkVersion().toByteArray()).isEqualTo(FORK_VERSION);
        assertThat(encoded.period()).isEqualTo(PERIOD);
        assertThat(encoded.currentOrThrow().pubkeysRoot().toByteArray()).isEqualTo(CURRENT.pubkeysRoot32());
        assertThat(encoded.currentOrThrow().aggregatePubkey().toByteArray()).isEqualTo(CURRENT.aggregatePubkey48());
        assertThat(encoded.nextOrThrow().pubkeysRoot().toByteArray()).isEqualTo(NEXT.pubkeysRoot32());
        assertThat(encoded.nextOrThrow().aggregatePubkey().toByteArray()).isEqualTo(NEXT.aggregatePubkey48());
        assertThat(encoded.codeHash().toByteArray()).isEqualTo(CODE_HASH);
    }

    @Test
    @DisplayName("given an encoded anchor, then decode restores every field")
    void givenEncodedAnchor_thenDecodeRestoresEveryField() {
        final TrustAnchor decoded = TrustAnchor.decode(ANCHOR.encode());

        assertThat(decoded.preset()).isEqualTo(SyncCommitteePreset.MINIMAL);
        assertThat(decoded.genesisValidatorsRoot32()).isEqualTo(GENESIS_VALIDATORS_ROOT);
        assertThat(decoded.forkVersion4()).isEqualTo(FORK_VERSION);
        assertThat(decoded.period()).isEqualTo(PERIOD);
        assertThat(decoded.current().pubkeysRoot32()).isEqualTo(CURRENT.pubkeysRoot32());
        assertThat(decoded.current().aggregatePubkey48()).isEqualTo(CURRENT.aggregatePubkey48());
        assertThat(decoded.next().pubkeysRoot32()).isEqualTo(NEXT.pubkeysRoot32());
        assertThat(decoded.next().aggregatePubkey48()).isEqualTo(NEXT.aggregatePubkey48());
        assertThat(decoded.codeHash32()).isEqualTo(CODE_HASH);
    }

    @Test
    @DisplayName("given an anchor, then id is the period as 8 big-endian bytes")
    void givenAnchor_thenIdIsBigEndianPeriod() {
        assertThat(ANCHOR.id()).isEqualTo(new byte[] {0, 0, 0, 0, 0, 0, 0x01, 0x02});
    }

    @Test
    @DisplayName("given the anchor's period, then committeeFor returns the current committee")
    void givenAnchorPeriod_thenCommitteeForReturnsCurrent() {
        assertThat(ANCHOR.committeeFor(PERIOD)).isSameAs(CURRENT);
    }

    @Test
    @DisplayName("given the period after the anchor's, then committeeFor returns the next committee")
    void givenFollowingPeriod_thenCommitteeForReturnsNext() {
        assertThat(ANCHOR.committeeFor(PERIOD + 1)).isSameAs(NEXT);
    }

    @ParameterizedTest(name = "period offset {0}")
    @ValueSource(longs = {-1, 2})
    @DisplayName("given a period outside the anchor's two, then committeeFor rejects it")
    void givenPeriodOutsideWindow_thenCommitteeForThrows(final long offset) {
        assertThatThrownBy(() -> ANCHOR.committeeFor(PERIOD + offset))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("the trust anchor trusts committees for periods 258 and 259, not for period "
                        + (PERIOD + offset));
    }

    @Test
    @DisplayName("given a committee after next, then rotatedTo slides the window by one period and keeps the rest")
    void givenCommitteeAfterNext_thenRotatedToSlidesWindow() {
        final SyncCommittee afterNext = new SyncCommittee(deterministicBytes(32, 0x70), deterministicBytes(48, 0x80));

        final TrustAnchor rotated = ANCHOR.rotatedTo(afterNext);

        assertThat(rotated.period()).isEqualTo(PERIOD + 1);
        assertThat(rotated.current()).isSameAs(NEXT);
        assertThat(rotated.next()).isSameAs(afterNext);
        assertThat(rotated.preset()).isEqualTo(SyncCommitteePreset.MINIMAL);
        assertThat(rotated.genesisValidatorsRoot32()).isEqualTo(GENESIS_VALIDATORS_ROOT);
        assertThat(rotated.forkVersion4()).isEqualTo(FORK_VERSION);
        assertThat(rotated.codeHash32()).isEqualTo(CODE_HASH);
    }

    @Test
    @DisplayName("given bytes that are not a protobuf, then decode rejects them")
    void givenGarbage_thenDecodeThrows() {
        assertThatThrownBy(() -> TrustAnchor.decode(new byte[] {(byte) 0xFF, (byte) 0xFF}))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("trustAnchor is not a valid EthereumPosTrustAnchor");
    }

    @Test
    @DisplayName("given an unknown field, then decode rejects the anchor")
    void givenUnknownField_thenDecodeThrows() {
        assertThatThrownBy(() -> TrustAnchor.decode(appendUnknownField(ANCHOR.encode())))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("trustAnchor is not a valid EthereumPosTrustAnchor");
    }

    @Test
    @DisplayName("given no next committee, then decode rejects the anchor")
    void givenNoNextCommittee_thenDecodeThrows() throws ParseException {
        final byte[] encoded = modified(anchor -> anchor.next((EthereumPosCommitteeAnchor) null));
        assertThatThrownBy(() -> TrustAnchor.decode(encoded))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("trustAnchor must hold both the current and the next committee");
    }

    @Test
    @DisplayName("given a 31-byte code hash, then decode rejects the anchor")
    void givenShortCodeHash_thenDecodeThrows() throws ParseException {
        final byte[] encoded = modified(anchor -> anchor.codeHash(Bytes.wrap(new byte[31])));
        assertThatThrownBy(() -> TrustAnchor.decode(encoded))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("trustAnchor has a field of the wrong length");
    }

    @Test
    @DisplayName("given a zero preset code, then decode rejects the anchor")
    void givenZeroPresetCode_thenDecodeThrows() throws ParseException {
        final byte[] encoded = modified(anchor -> anchor.preset(0));
        assertThatThrownBy(() -> TrustAnchor.decode(encoded))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("unknown sync committee preset code 0");
    }

    /** {@link #ANCHOR}'s encoding with {@code change} applied to its protobuf. */
    private static byte[] modified(final UnaryOperator<EthereumPosTrustAnchor.Builder> change) throws ParseException {
        final EthereumPosTrustAnchor anchor = EthereumPosTrustAnchor.PROTOBUF.parse(Bytes.wrap(ANCHOR.encode()));
        return EthereumPosTrustAnchor.PROTOBUF
                .toBytes(change.apply(anchor.copyBuilder()).build())
                .toByteArray();
    }
}
