// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventHashFactoryTest {

    private static final long CUTOVER = 100;

    private Randotron random;

    @BeforeEach
    void setUp() {
        random = Randotron.create();
    }

    @AfterEach
    void tearDown() {
        // restore the uninitialized sentinel so tests do not leak static state
        EventHashFactory.initialize(-1);
    }

    @Test
    void birthRoundBelowCutoverIsSha384() {
        EventHashFactory.initialize(CUTOVER);
        final Bytes bytes = randomBytes(DigestType.SHA_384);

        final Hash hash = EventHashFactory.hash(bytes, CUTOVER - 1);

        assertThat(hash.getDigestType()).isEqualTo(DigestType.SHA_384);
        assertThat(hash.getBytes()).isEqualTo(bytes);
    }

    @Test
    void birthRoundAtCutoverIsSha256() {
        EventHashFactory.initialize(CUTOVER);
        final Bytes bytes = randomBytes(DigestType.SHA_256);

        final Hash hash = EventHashFactory.hash(bytes, CUTOVER);

        assertThat(hash.getDigestType()).isEqualTo(DigestType.SHA_256);
        assertThat(hash.getBytes()).isEqualTo(bytes);
    }

    @Test
    void birthRoundAboveCutoverIsSha256() {
        EventHashFactory.initialize(CUTOVER);
        final Bytes bytes = randomBytes(DigestType.SHA_256);

        final Hash hash = EventHashFactory.hash(bytes, CUTOVER + 1);

        assertThat(hash.getDigestType()).isEqualTo(DigestType.SHA_256);
        assertThat(hash.getBytes()).isEqualTo(bytes);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, CUTOVER, Long.MAX_VALUE - 1})
    void noCutoverAlwaysSha384(final long birthRound) {
        EventHashFactory.initialize(Long.MAX_VALUE);

        final Hash hash = EventHashFactory.hash(randomBytes(DigestType.SHA_384), birthRound);

        assertThat(hash.getDigestType()).isEqualTo(DigestType.SHA_384);
    }

    @Test
    void wrongLengthBytesRejected() {
        EventHashFactory.initialize(CUTOVER);

        final Bytes sha256Bytes = randomBytes(DigestType.SHA_256);
        assertThatThrownBy(() -> EventHashFactory.hash(sha256Bytes, CUTOVER - 1))
                .isInstanceOf(IllegalArgumentException.class);

        final Bytes sha384Bytes = randomBytes(DigestType.SHA_384);
        assertThatThrownBy(() -> EventHashFactory.hash(sha384Bytes, CUTOVER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reinitializeChangesCutover() {
        EventHashFactory.initialize(CUTOVER);
        assertThat(EventHashFactory.hash(randomBytes(DigestType.SHA_256), CUTOVER)
                        .getDigestType())
                .isEqualTo(DigestType.SHA_256);

        EventHashFactory.initialize(CUTOVER + 1);
        assertThat(EventHashFactory.hash(randomBytes(DigestType.SHA_384), CUTOVER)
                        .getDigestType())
                .isEqualTo(DigestType.SHA_384);
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void hashFromBytesUsesDigestTypeMatchingLength(final DigestType digestType) {
        final Bytes bytes = randomBytes(digestType);

        final Hash hash = EventHashFactory.hash(bytes);

        assertThat(hash.getDigestType()).isEqualTo(digestType);
        assertThat(hash.getBytes()).isEqualTo(bytes);
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void hashFromByteArrayUsesDigestTypeMatchingLength(final DigestType digestType) {
        final Bytes bytes = randomBytes(digestType);

        final Hash hash = EventHashFactory.hash(bytes.toByteArray());

        assertThat(hash.getDigestType()).isEqualTo(digestType);
        assertThat(hash.getBytes()).isEqualTo(bytes);
    }

    @Test
    void hashFromLengthDoesNotRequireInitialization() {
        // the length based methods do not depend on the cutover, so they work while the factory is uninitialized
        final Bytes bytes = randomBytes(DigestType.SHA_256);

        assertThat(EventHashFactory.hash(bytes).getDigestType()).isEqualTo(DigestType.SHA_256);
        assertThat(EventHashFactory.hash(bytes.toByteArray()).getDigestType()).isEqualTo(DigestType.SHA_256);
    }

    @Test
    void hashFromLengthIgnoresCutover() {
        // the digest type is chosen by length only, even for lengths that disagree with the cutover
        EventHashFactory.initialize(CUTOVER);

        assertThat(EventHashFactory.hash(randomBytes(DigestType.SHA_384)).getDigestType())
                .isEqualTo(DigestType.SHA_384);
        assertThat(EventHashFactory.hash(randomBytes(DigestType.SHA_256)).getDigestType())
                .isEqualTo(DigestType.SHA_256);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31, 33, 47, 49, 63, 65, 128})
    void hashFromBytesWithUnknownLengthRejected(final int length) {
        final byte[] bytes = new byte[length];
        random.nextBytes(bytes);

        assertThatThrownBy(() -> EventHashFactory.hash(Bytes.wrap(bytes))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventHashFactory.hash(bytes)).isInstanceOf(IllegalArgumentException.class);
    }

    @NonNull
    private Bytes randomBytes(@NonNull final DigestType digestType) {
        final byte[] bytes = new byte[digestType.digestLength()];
        random.nextBytes(bytes);
        return Bytes.wrap(bytes);
    }
}
