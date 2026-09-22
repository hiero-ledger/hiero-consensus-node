// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.Code;

/**
 * A process-wide cache of parsed {@link Code} objects and their Tuweni bytecode representation, keyed by the
 * (immutable) contract bytecode content.
 *
 * <p>Besu's {@link Code} computes its Keccak {@code codeHash} and jump-destination bit mask lazily and caches
 * them <i>per instance</i>. Because {@code FrameBuilder} and {@code DispatchingEvmFrameState} previously built a
 * fresh {@code new Code(...)} on every transaction, that per-instance cache was thrown away on each call, so the
 * full-bytecode Keccak (and jump-dest analysis) was recomputed for every execution — profiling showed this
 * dominating ~30% of consensus CPU. Caching the {@link Code} amortizes both to <i>once per distinct bytecode</i>.
 *
 * <p>The cache is keyed by bytecode <b>content</b> via {@link BytecodeContentKey} (wrapping PBJ {@link Bytes},
 * which has content-based {@code equals}/{@code hashCode}). Content keying is what makes this safe on the
 * consensus path: the code hash and jump-dest mask are pure functions of the bytecode, so identical bytecode
 * always yields the identical {@link CachedBytecode}, regardless of which contract id holds it. There is therefore
 * no invalidation to get wrong (no staleness on code change, contract id reuse, or savepoint rollback); a different
 * or removed bytecode is simply a different (or absent) key. A cache miss only re-derives the same value, so
 * eviction and cache size never affect transaction results — only performance — and the result is identical across
 * all nodes.
 */
@Singleton
public class ContractCodeCache {
    /**
     * Approximate upper bound on the total bytecode bytes retained by the cache. With the EVM contract size cap
     * (~24 KiB) this admits tens of thousands of distinct contracts; misses beyond this simply recompute.
     */
    private static final long MAX_WEIGHT_BYTES = 256L * 1024L * 1024L;

    private final LoadingCache<BytecodeContentKey, CachedBytecode> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT_BYTES)
            .weigher((BytecodeContentKey key, CachedBytecode value) ->
                    (int) Math.min(Integer.MAX_VALUE, key.content().length()))
            .build(key -> CachedBytecode.fromPbj(key.content()));

    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractCodeCache() {
        // Dagger2
    }

    /**
     * Returns the parsed {@link Code} for the given contract bytecode, computing (and caching) its Keccak code
     * hash and jump-destination analysis on first sight and reusing them on subsequent calls.
     *
     * @param bytecode the contract bytecode
     * @return the parsed {@link Code}
     */
    public @NonNull Code getCode(@NonNull final Bytes bytecode) {
        return cache.get(new BytecodeContentKey(bytecode)).code();
    }

    /**
     * Returns the Tuweni bytecode for the given contract bytecode, reusing the cached PBJ-to-Tuweni conversion
     * and {@link Code} instance on subsequent calls.
     *
     * @param bytecode the contract bytecode
     * @return the Tuweni bytecode
     */
    public @NonNull org.apache.tuweni.bytes.Bytes getTuweniBytecode(@NonNull final Bytes bytecode) {
        return cache.get(new BytecodeContentKey(bytecode)).tuweniBytes();
    }

    /**
     * Returns the parsed {@link Code} for the given Tuweni bytecode. Prefer {@link #getCode(Bytes)} with
     * {@link com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount#getCodePBJ()} when available,
     * since this path copies bytes to build the PBJ cache key.
     *
     * @param bytecode the Tuweni contract bytecode
     * @return the parsed {@link Code}
     */
    public @NonNull Code getCodeFromTuweni(@NonNull final org.apache.tuweni.bytes.Bytes bytecode) {
        if (bytecode.isEmpty()) {
            return Code.EMPTY_CODE;
        }
        return getCode(tuweniToPbjBytes(bytecode));
    }

    /**
     * Content-based cache key for contract bytecode. Distinct from {@link com.hedera.hapi.node.base.ContractID}
     * so callers cannot accidentally key the cache by entity id (which would require invalidation on code updates).
     *
     * @param content the immutable PBJ bytecode content
     */
    record BytecodeContentKey(@NonNull Bytes content) {
        BytecodeContentKey {
            requireNonNull(content);
        }
    }

    /**
     * A cached pair of parsed {@link Code} and its Tuweni bytecode bytes, derived together on first sight.
     *
     * @param code the parsed Besu code
     * @param tuweniBytes the Tuweni bytecode backing the parsed code
     */
    record CachedBytecode(@NonNull Code code, @NonNull org.apache.tuweni.bytes.Bytes tuweniBytes) {
        CachedBytecode {
            requireNonNull(code);
            requireNonNull(tuweniBytes);
        }

        private static @NonNull CachedBytecode fromPbj(@NonNull final Bytes bytecode) {
            final var tuweniBytes = pbjToTuweniBytes(bytecode);
            return new CachedBytecode(new Code(tuweniBytes), tuweniBytes);
        }
    }
}
