// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeyRequirementTest {
    private static final Key SHARED_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("11".repeat(32))).build();
    private static final AccountID A = AccountID.newBuilder().accountNum(123L).build();
    private static final AccountID C = AccountID.newBuilder().accountNum(456L).build();

    @Test
    void identicalKeyBytesDoNotMergeAuthorities() {
        assertThat(Set.of(
                        KeyRequirement.forAccount(A, SHARED_KEY),
                        KeyRequirement.forAccount(C, SHARED_KEY),
                        KeyRequirement.forKey(SHARED_KEY)))
                .hasSize(3);
        assertThat(KeyRequirement.forAccount(A, SHARED_KEY))
                .isNotEqualTo(
                        KeyRequirement.forAccount(A.copyBuilder().shardNum(1L).build(), SHARED_KEY));
    }

    @Test
    void aliasesMustBeResolvedBeforeTheyCanAuthorizeAnAccount() {
        final var alias = AccountID.newBuilder().alias(Bytes.wrap(new byte[20])).build();
        assertThatThrownBy(() -> KeyRequirement.forAccount(alias, SHARED_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alternativesRetainIndependentScopesAndSnapshotTheirInput() {
        final var owner = KeyRequirement.forAccount(A, SHARED_KEY);
        final var explicit = KeyRequirement.forKey(SHARED_KEY);
        final var inputs = new ArrayList<>(List.of(owner, explicit));
        final var requirement = new KeyRequirement.AnyOf(inputs);
        inputs.clear();

        assertThat(requirement.alternatives()).containsExactly(owner, explicit);
        assertThat(requirement.key().thresholdKeyOrThrow().threshold()).isEqualTo(1);
        assertThat(requirement.key().thresholdKeyOrThrow().keysOrThrow().keys())
                .containsExactly(SHARED_KEY, SHARED_KEY);
        assertThatThrownBy(() -> requirement.alternatives().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyAlternativesCannotAuthorizeAnOperation() {
        assertThatThrownBy(() -> KeyRequirement.anyOf(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
