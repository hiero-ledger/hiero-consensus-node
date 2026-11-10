// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The Ethereum consensus-spec presets whose sync-committee parameters the verifier supports.
 *
 * <p>The consensus specs define exactly two presets, {@code mainnet} and {@code minimal}, in
 * <a href="https://github.com/ethereum/consensus-specs/tree/master/presets">ethereum/consensus-specs/presets</a>.
 * Committee size and period length are preset constants that clients compile in, not runtime configuration: each
 * value below comes from {@code SYNC_COMMITTEE_SIZE} and {@code EPOCHS_PER_SYNC_COMMITTEE_PERIOD} in the preset's
 * {@code altair.yaml}, and {@code SLOTS_PER_EPOCH} in its {@code phase0.yaml}. The public networks (mainnet and the
 * testnets) use {@code mainnet}; local devnets usually use {@code minimal}.
 *
 * <p>A channel's preset is fixed by {@code verifyConfig}, from the size of the trusted initial committee, and stored
 * in the trust anchor. Bundles never choose it: a bundle able to pick a smaller committee could lower the number of
 * keys an attacker must control to forge a signature.
 */
enum SyncCommitteePreset {
    /**
     * The {@code mainnet} preset: 512 members, 256 epochs of 32 slots per period.
     *
     * @see <a href="https://github.com/ethereum/consensus-specs/blob/master/presets/mainnet/altair.yaml">mainnet
     *     altair.yaml</a>
     */
    MAINNET(1, 512, 8192),
    /**
     * The {@code minimal} preset, used by local devnets: 32 members, 8 epochs of 8 slots per period.
     *
     * @see <a href="https://github.com/ethereum/consensus-specs/blob/master/presets/minimal/altair.yaml">minimal
     *     altair.yaml</a>
     */
    MINIMAL(2, 32, 64);

    private final int code;
    private final int committeeSize;
    private final long slotsPerPeriod;

    SyncCommitteePreset(final int code, final int committeeSize, final long slotsPerPeriod) {
        this.code = code;
        this.committeeSize = committeeSize;
        this.slotsPerPeriod = slotsPerPeriod;
    }

    /**
     * Returns the preset whose committee has {@code committeeSize} members.
     *
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if no supported preset has that size
     */
    @NonNull
    static SyncCommitteePreset forCommitteeSize(final int committeeSize) {
        for (final SyncCommitteePreset preset : values()) {
            if (preset.committeeSize == committeeSize) {
                return preset;
            }
        }
        throw EthProofs.fail("unsupported sync committee size " + committeeSize + "; expected " + MAINNET.committeeSize
                + " (mainnet) or " + MINIMAL.committeeSize + " (minimal)");
    }

    /**
     * Returns the preset with the given trust-anchor code.
     *
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if the code is unknown
     */
    @NonNull
    static SyncCommitteePreset fromCode(final int code) {
        for (final SyncCommitteePreset preset : values()) {
            if (preset.code == code) {
                return preset;
            }
        }
        throw EthProofs.fail("unknown sync committee preset code " + code);
    }

    /** The code that identifies this preset in the trust anchor. */
    int code() {
        return code;
    }

    /** The number of sync-committee members. */
    int committeeSize() {
        return committeeSize;
    }

    /** The length in bytes of the participation {@code Bitvector}. */
    int bitsLength() {
        return committeeSize / Byte.SIZE;
    }

    /** The depth of the SSZ tree over the member keys, i.e. the sibling count of a non-signer proof. */
    int pubkeysBranchDepth() {
        return Integer.numberOfTrailingZeros(committeeSize);
    }

    /** The sync-committee period of {@code slot}, reading the slot as an unsigned {@code uint64}. */
    long periodOf(final long slot) {
        return Long.divideUnsigned(slot, slotsPerPeriod);
    }

    /** Whether {@code participants} members are at least the 2/3 supermajority of the committee. */
    boolean isSupermajority(final int participants) {
        return 3 * participants >= 2 * committeeSize;
    }
}
