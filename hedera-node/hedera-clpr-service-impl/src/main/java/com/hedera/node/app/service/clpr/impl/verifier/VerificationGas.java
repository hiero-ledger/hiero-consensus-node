// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Meters the gas a proof verification uses, so a verifier system contract charges by the work done instead of a flat
 * amount.
 *
 * <p>The meter is bound for the duration of one call with {@link #metered}. The verification code charges at the
 * points where the work happens ({@link #charge}), so the work is bounded by the gas the caller provided: once the
 * limit is passed, the next charge throws a {@link ProofException} and the meter reports {@link #exhausted()}.
 * Outside a metered call, {@link #charge} does nothing.
 */
public final class VerificationGas {
    private static final ScopedValue<VerificationGas> CURRENT = ScopedValue.newInstance();

    private final long limit;
    private long used;
    private boolean exhausted;

    /**
     * @param limit the most gas the verification may use, typically the gas left in the calling frame
     */
    public VerificationGas(final long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("gas limit is negative");
        }
        this.limit = limit;
    }

    /**
     * Runs {@code operation} with {@code meter} bound, so the {@link #charge} calls it makes are counted by it.
     */
    public static <T, X extends Throwable> T metered(
            @NonNull final VerificationGas meter, @NonNull final ScopedValue.CallableOp<T, X> operation) throws X {
        return ScopedValue.where(CURRENT, meter).call(operation);
    }

    /**
     * Charges {@code gas} to the bound meter; does nothing when no meter is bound.
     *
     * @throws ProofException if the charge takes the meter past its limit
     */
    public static void charge(final long gas) {
        if (CURRENT.isBound()) {
            CURRENT.get().add(gas);
        }
    }

    /** The gas of hashing {@code length} bytes with keccak256, as the EVM charges it: 30 plus 6 per 32-byte word. */
    public static long keccakGas(final int length) {
        return 30L + 6L * ((length + 31L) / 32L);
    }

    /** The gas charged so far. */
    public long used() {
        return used;
    }

    /** Whether a charge went past the limit. */
    public boolean exhausted() {
        return exhausted;
    }

    private void add(final long gas) {
        if (gas < 0) {
            throw new IllegalArgumentException("gas charge is negative");
        }
        used = gas > Long.MAX_VALUE - used ? Long.MAX_VALUE : used + gas;
        if (used > limit) {
            exhausted = true;
            throw new ProofException("VerificationGas", "out of gas: used " + used + ", limit " + limit);
        }
    }
}
