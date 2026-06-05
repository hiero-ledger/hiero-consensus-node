// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

/**
 * A primitive-keyed identifier for a slot in contract storage.
 *
 * <p>Encodes a slot as the contract entity number plus the four big-endian {@code long} words of the
 * 32-byte EVM key. Used inside {@link DispatchingEvmFrameState} as a cache key for the per-frame
 * {@link com.hedera.hapi.node.state.contract.SlotKey} flyweight cache and the per-frame slot value
 * caches, allowing the hot SLOAD/SSTORE path to avoid allocating a fresh PBJ {@code SlotKey}/{@code Bytes}
 * pair on every access.
 *
 * <p>Mutable so a single shared probe instance can be reused for {@code HashMap.get} lookups; on a
 * cache miss callers must store an immutable {@link #copy()} as the map key. Not thread-safe; one
 * instance per {@link DispatchingEvmFrameState} (which is itself per-frame).
 */
final class SlotLookupKey {
    private long contractNum;
    private long w0;
    private long w1;
    private long w2;
    private long w3;

    SlotLookupKey() {}

    private SlotLookupKey(final long contractNum, final long w0, final long w1, final long w2, final long w3) {
        this.contractNum = contractNum;
        this.w0 = w0;
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
    }

    void set(final long contractNum, final long w0, final long w1, final long w2, final long w3) {
        this.contractNum = contractNum;
        this.w0 = w0;
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
    }

    SlotLookupKey copy() {
        return new SlotLookupKey(contractNum, w0, w1, w2, w3);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SlotLookupKey that)) {
            return false;
        }
        return contractNum == that.contractNum && w0 == that.w0 && w1 == that.w1 && w2 == that.w2 && w3 == that.w3;
    }

    @Override
    public int hashCode() {
        long h = contractNum;
        h = h * 31 + w0;
        h = h * 31 + w1;
        h = h * 31 + w2;
        h = h * 31 + w3;
        return Long.hashCode(h);
    }
}
