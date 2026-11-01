// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * An authorization requirement, including the identity whose involvement requires authorization.
 *
 * <p>Equal keys can occur in different requirements. Satisfying an account requirement through that
 * account's execution does not establish a signature for its key or satisfy another account's
 * requirement. The {@link #key()} projection is suitable for signature expansion and accounting,
 * but must not be used to discard the identity when checking authorization.
 */
public sealed interface KeyRequirement {
    /** Returns the key structure whose signatures can satisfy this requirement. */
    @NonNull
    Key key();

    /**
     * Creates a requirement from an account's canonical ID and controlling key read together from trusted state.
     * This factory validates structure only; callers must not supply an identity or key from transaction input.
     * Prefer {@link com.hedera.node.app.spi.workflows.PreHandleContext#keyRequirementFor} when collecting requirements.
     */
    @NonNull
    static KeyRequirement forAccount(@NonNull final AccountID accountId, @NonNull final Key key) {
        return new AccountKey(accountId, key);
    }

    /** Creates an explicit key requirement, which has no account-execution exemption. */
    @NonNull
    static KeyRequirement forKey(@NonNull final Key key) {
        return new ExplicitKey(key);
    }

    /** Creates alternatives whose identities remain independent, even when their keys are equal. */
    @NonNull
    static KeyRequirement anyOf(@NonNull final List<KeyRequirement> alternatives) {
        return new AnyOf(alternatives);
    }

    /** Authorization by the account identified by its canonical shard, realm, and account number. */
    record AccountKey(@NonNull AccountID accountId, @NonNull Key key) implements KeyRequirement {
        public AccountKey {
            requireNonNull(accountId);
            requireNonNull(key);
            if (!accountId.hasAccountNum()) {
                throw new IllegalArgumentException("Account requirements must use a canonical numeric account ID");
            }
        }
    }

    /** Authorization by an explicitly specified key, such as a new admin key or a token supply key. */
    record ExplicitKey(@NonNull Key key) implements KeyRequirement {
        public ExplicitKey {
            requireNonNull(key);
        }
    }

    /** Authorization by at least one alternative, retaining each alternative's own identity. */
    record AnyOf(@NonNull List<KeyRequirement> alternatives) implements KeyRequirement {
        public AnyOf {
            alternatives = List.copyOf(requireNonNull(alternatives));
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("At least one authorization alternative is required");
            }
        }

        @Override
        public @NonNull Key key() {
            return Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .threshold(1)
                            .keys(new KeyList(alternatives.stream()
                                    .map(KeyRequirement::key)
                                    .toList())))
                    .build();
        }
    }
}
