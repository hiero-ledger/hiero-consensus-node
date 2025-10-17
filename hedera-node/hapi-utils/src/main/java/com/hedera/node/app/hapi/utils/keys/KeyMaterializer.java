// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.IndirectKey;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utility to materialize a Key by inlining all IndirectKey references.
 *
 * Behavior:
 * - When encountering an IndirectKey(account), fetch the target account's materialized key if present,
 *   else its template key, and inline that subtree.
 * - Detect cycles (e.g., A -> B -> A) using a path-local visited set of AccountID.
 * - Enforce configurable limits on max depth and max indirections.
 */
public final class KeyMaterializer {
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static final int DEFAULT_MAX_INDIRECTIONS = 100;

    private final int maxDepth;
    private final int maxIndirections;

    /** Abstraction to fetch account keys without binding to a specific store implementation. */
    public interface KeySource {
        /** Returns the materialized key for the given account, or null if not set. */
        Key materializedKey(@NonNull AccountID id);
        /** Returns the template key for the given account, or null if not set. */
        Key templateKey(@NonNull AccountID id);
    }

    /** Exception thrown on cycle detection or limit breaches. */
    public static class MaterializationException extends RuntimeException {
        public MaterializationException(final String message) {
            super(message);
        }
    }

    public KeyMaterializer() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_INDIRECTIONS);
    }

    public KeyMaterializer(final int maxDepth, final int maxIndirections) {
        if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be positive");
        if (maxIndirections < 0) throw new IllegalArgumentException("maxIndirections must be >= 0");
        this.maxDepth = maxDepth;
        this.maxIndirections = maxIndirections;
    }

    /**
     * Materializes the given template key for the specified root account.
     *
     * @param rootAccount the account whose key is being materialized (used for cycle detection)
     * @param template the template key to materialize
     * @param source source of account keys
     * @return fully materialized key
     * @throws MaterializationException on cycle detection or limit violations
     */
    public Key materialize(
            @NonNull final AccountID rootAccount, @NonNull final Key template, @NonNull final KeySource source) {
        requireNonNull(rootAccount, "rootAccount");
        requireNonNull(template, "template");
        requireNonNull(source, "source");
        final Set<AccountID> path = new HashSet<>();
        path.add(rootAccount);
        final Counter indirections = new Counter();
        return materializeRec(template, path, 0, indirections, source);
    }

    private Key materializeRec(
            final Key key,
            final Set<AccountID> path,
            final int depth,
            final Counter indirections,
            final KeySource source) {
        if (depth > maxDepth) {
            throw new MaterializationException("Exceeded max depth " + maxDepth);
        }
        if (key == null || key.key() == null) {
            return key; // UNSET or null
        }
        return switch (key.key().kind()) {
            case UNSET -> key;
            case ED25519, ECDSA_SECP256K1, RSA_3072, ECDSA_384, CONTRACT_ID, DELEGATABLE_CONTRACT_ID -> key;
            case KEY_LIST -> materializeKeyList(key, path, depth, indirections, source);
            case THRESHOLD_KEY -> materializeThreshold(key, path, depth, indirections, source);
            case INDIRECT_KEY -> materializeIndirect(key, path, depth, indirections, source);
        };
    }

    private Key materializeKeyList(
            final Key key,
            final Set<AccountID> path,
            final int depth,
            final Counter indirections,
            final KeySource source) {
        final KeyList list = key.keyList();
        if (list == null || list.keys() == null || list.keys().isEmpty()) return key;
        final List<Key> resolved = new ArrayList<>(list.keys().size());
        for (final Key child : list.keys()) {
            resolved.add(materializeRec(child, path, depth + 1, indirections, source));
        }
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(resolved)).build();
    }

    private Key materializeThreshold(
            final Key key,
            final Set<AccountID> path,
            final int depth,
            final Counter indirections,
            final KeySource source) {
        final ThresholdKey t = key.thresholdKey();
        if (t == null) return key;
        final KeyList keys = t.keys();
        final List<Key> resolved = (keys == null || keys.keys() == null)
                ? List.of()
                : keys.keys().stream()
                        .map(k -> materializeRec(k, path, depth + 1, indirections, source))
                        .toList();
        final ThresholdKey newT = ThresholdKey.newBuilder()
                .threshold(t.threshold())
                .keys(KeyList.newBuilder().keys(resolved))
                .build();
        return Key.newBuilder().thresholdKey(newT).build();
    }

    private Key materializeIndirect(
            final Key key,
            final Set<AccountID> path,
            final int depth,
            final Counter indirections,
            final KeySource source) {
        if (indirections.value >= maxIndirections) {
            throw new MaterializationException("Exceeded max indirections " + maxIndirections);
        }
        final IndirectKey indirect = key.indirectKey();
        if (indirect == null || indirect.target() == null) return key;
        switch (indirect.target().kind()) {
            case ACCOUNT_ID -> {
                final AccountID id = Objects.requireNonNull(indirect.accountId());
                if (path.contains(id)) {
                    throw new MaterializationException("Indirect key cycle detected at account " + id);
                }
                path.add(id);
                try {
                    indirections.value++;
                    Key inline = source.materializedKey(id);
                    if (inline == null || inline.key() == null) {
                        inline = source.templateKey(id);
                    }
                    if (inline == null) {
                        throw new MaterializationException("No key available for account " + id);
                    }
                    return materializeRec(inline, path, depth + 1, indirections, source);
                } finally {
                    path.remove(id);
                }
            }
            case CONTRACT_ID -> {
                // Placeholder: Not yet supported; specification focuses on AccountID for now.
                throw new MaterializationException("IndirectKey to ContractID is not yet supported");
            }
            case UNSET -> {
                return key;
            }
        }
        return key;
    }

    private static final class Counter {
        int value = 0;
    }
}
