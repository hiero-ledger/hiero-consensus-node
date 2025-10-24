// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KEY_ENCODING;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ThresholdKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to materialize a {@link Key} by inlining all IndirectKey references from a {@link Source}.
 * <p>
 * Fails if the implied key exceeds the maximum depth, or references a non-existent account
 * or contract according to the {@link Source}.
 */
public final class KeyMaterializer {
    public static final int DEFAULT_MAX_DEPTH = 15;

    private final int maxDepth;

    /**
     *  Abstraction to fetch account keys without binding to a specific store implementation.
     * */
    public interface Source {
        /**
         * Returns the materialized key for the given account.
         * @throws NullPointerException if the account does not exist
         */
        Key materializedKeyOrThrow(@NonNull AccountID id);

        /**
         * Returns the materialized key for the given account.
         * @throws NullPointerException if the account does not exist
         */
        Key materializedKeyOrThrow(@NonNull ContractID id);
    }

    /** Exception thrown on cycle detection or limit breaches. */
    public static class MaterializationException extends RuntimeException {
        private final ResponseCodeEnum status;

        public MaterializationException(@NonNull final ResponseCodeEnum status) {
            this.status = requireNonNull(status);
        }

        /**
         *  Returns the status code for the exception.
         */
        public ResponseCodeEnum getStatus() {
            return status;
        }
    }

    public KeyMaterializer() {
        this(DEFAULT_MAX_DEPTH);
    }

    public KeyMaterializer(final int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        this.maxDepth = maxDepth;
    }

    /**
     * Materializes the given template key using the given source of account keys.
     * @param template the template key to materialize
     * @param source source of account keys
     * @return fully materialized key
     * @throws MaterializationException on failure
     */
    public Key materialize(@NonNull final Key template, @NonNull final Source source) {
        requireNonNull(template);
        requireNonNull(source);
        return materializeRec(template, 0, source);
    }

    private Key materializeRec(@NonNull final Key key, final int depth, @NonNull final Source source) {
        if (depth > maxDepth) {
            throw new MaterializationException(INVALID_KEY_ENCODING);
        }
        return switch (key.key().kind()) {
            case KEY_LIST -> materializeKeyList(key, depth, source);
            case THRESHOLD_KEY -> materializeThreshold(key, depth, source);
            case INDIRECT_KEY -> materializeIndirect(key, depth, source);
            default -> key;
        };
    }

    private Key materializeKeyList(@NonNull final Key key, final int depth, @NonNull final Source source) {
        final var list = key.keyListOrThrow();
        if (list.keys().isEmpty()) {
            return key;
        }
        final List<Key> resolved = new ArrayList<>(list.keys().size());
        for (final var child : list.keys()) {
            resolved.add(materializeRec(child, depth + 1, source));
        }
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(resolved)).build();
    }

    private Key materializeThreshold(@NonNull final Key key, final int depth, @NonNull final Source source) {
        final var t = key.thresholdKeyOrThrow();
        final var keys = t.keys();
        final List<Key> resolved = keys == null
                ? List.of()
                : keys.keys().stream()
                        .map(k -> materializeRec(k, depth + 1, source))
                        .toList();
        final var newT = ThresholdKey.newBuilder()
                .threshold(t.threshold())
                .keys(KeyList.newBuilder().keys(resolved))
                .build();
        return Key.newBuilder().thresholdKey(newT).build();
    }

    private Key materializeIndirect(@NonNull final Key key, final int depth, @NonNull final Source source) {
        final var indirect = key.indirectKeyOrThrow();
        switch (indirect.target().kind()) {
            case ACCOUNT_ID -> {
                try {
                    final var inline = source.materializedKeyOrThrow(indirect.accountIdOrThrow());
                    return materializeRec(inline, depth + 1, source);
                } catch (NullPointerException e) {
                    throw new MaterializationException(INVALID_ACCOUNT_ID);
                }
            }
            case CONTRACT_ID -> {
                try {
                    final var inline = source.materializedKeyOrThrow(indirect.contractIdOrThrow());
                    return materializeRec(inline, depth + 1, source);
                } catch (NullPointerException e) {
                    throw new MaterializationException(INVALID_CONTRACT_ID);
                }
            }
            case UNSET -> {
                return key;
            }
        }
        return key;
    }
}
