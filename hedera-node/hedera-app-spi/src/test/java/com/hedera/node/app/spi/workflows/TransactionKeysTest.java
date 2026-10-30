// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionKeysTest {
    private static final Key PAYER_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("11".repeat(32))).build();
    private static final Key REQUIRED_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("22".repeat(32))).build();
    private static final Key OPTIONAL_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("33".repeat(32))).build();

    private final TransactionKeys subject = new TransactionKeys() {
        @Override
        public Key payerKey() {
            return PAYER_KEY;
        }

        @Override
        public Set<Key> requiredNonPayerKeys() {
            return Set.of(REQUIRED_KEY);
        }

        @Override
        public Set<Account> requiredHollowAccounts() {
            return Set.of();
        }

        @Override
        public Set<Key> optionalNonPayerKeys() {
            return Set.of(OPTIONAL_KEY);
        }

        @Override
        public Set<Account> optionalHollowAccounts() {
            return Set.of();
        }
    };

    @Test
    void defaultRequirementsAreExplicitKeyProjections() {
        assertThat(subject.payerKeyRequirement()).isEqualTo(KeyRequirement.forKey(PAYER_KEY));
        assertThat(subject.requiredKeyRequirements()).containsExactly(KeyRequirement.forKey(REQUIRED_KEY));
        assertThat(subject.optionalKeyRequirements()).containsExactly(KeyRequirement.forKey(OPTIONAL_KEY));
    }
}
