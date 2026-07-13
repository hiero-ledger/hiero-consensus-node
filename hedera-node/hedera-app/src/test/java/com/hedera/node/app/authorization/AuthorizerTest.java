// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class AuthorizerTest {
    private ConfigProvider configProvider;

    @Mock
    private PrivilegesVerifier privilegesVerifier;

    private AccountID accountID;
    private HederaFunctionality hapiFunction;

    @BeforeEach
    void setUp() {
        configProvider = () -> new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);

        accountID = AccountID.newBuilder().build();
        hapiFunction = CONSENSUS_CREATE_TOPIC;
    }

    @Test
    @DisplayName("Account ID is null throws")
    void accountIdIsNullThrows() {
        // given:
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        // expect:
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> authorizer.isAuthorized(null, hapiFunction)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Hapi function is null throws")
    void hapiFunctionIsNullThrows() {
        // given:
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        // expect:
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> authorizer.isAuthorized(accountID, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Account is not permitted")
    void accountIsNotPermitted() {
        // given:
        configProvider = () -> new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("createTopic", "1-1000")
                        .getOrCreateConfig(),
                1);

        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);
        accountID = AccountID.newBuilder().accountNum(1234L).build();

        // expect:
        final var authorized = authorizer.isAuthorized(accountID, hapiFunction);
        assertThat(authorized).isFalse();
    }

    @Test
    @DisplayName("Account is permitted")
    void accountIsPermitted() {
        // given:
        configProvider = () -> new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("createTopic", "1-1234")
                        .getOrCreateConfig(),
                1);

        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);
        accountID = AccountID.newBuilder().accountNum(1234L).build();

        // expect:
        final var authorized = authorizer.isAuthorized(accountID, hapiFunction);
        assertThat(authorized).isTrue();
    }

    @Test
    @DisplayName("Super-user status requires the configured shard and realm")
    void superUserRequiresMatchingShardAndRealm() {
        // given the default ledger shard/realm of 0.0, with treasury=2 and systemAdmin=50:
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        // the canonical treasury and system-admin accounts are super-users:
        assertThat(authorizer.isSuperUser(accountId(0, 0, 2))).isTrue();
        assertThat(authorizer.isSuperUser(accountId(0, 0, 50))).isTrue();

        // but an id that only matches on the account number is not, regardless of shard or realm:
        assertThat(authorizer.isSuperUser(accountId(1, 0, 2))).isFalse();
        assertThat(authorizer.isSuperUser(accountId(0, 1, 2))).isFalse();
        assertThat(authorizer.isSuperUser(accountId(1, 0, 50))).isFalse();
        assertThat(authorizer.isSuperUser(accountId(0, 1, 50))).isFalse();
    }

    @Test
    @DisplayName("Treasury status requires the configured shard and realm")
    void treasuryRequiresMatchingShardAndRealm() {
        // given the default ledger shard/realm of 0.0, with treasury=2:
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        assertThat(authorizer.isTreasury(accountId(0, 0, 2))).isTrue();
        assertThat(authorizer.isTreasury(accountId(1, 0, 2))).isFalse();
        assertThat(authorizer.isTreasury(accountId(0, 1, 2))).isFalse();
    }

    @Test
    @DisplayName("Super-user status is scoped to a non-zero configured shard and realm")
    void superUserScopedToConfiguredNonZeroShardRealm() {
        // given a ledger configured for shard 1, realm 2:
        configProvider = () -> new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("hedera.shard", "1")
                        .withValue("hedera.realm", "2")
                        .getOrCreateConfig(),
                1);
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        // the treasury account lives at 1.2.2 and is a super-user:
        assertThat(authorizer.isSuperUser(accountId(1, 2, 2))).isTrue();
        assertThat(authorizer.isTreasury(accountId(1, 2, 2))).isTrue();

        // while the same number in shard/realm 0.0 is not:
        assertThat(authorizer.isSuperUser(accountId(0, 0, 2))).isFalse();
        assertThat(authorizer.isTreasury(accountId(0, 0, 2))).isFalse();
    }

    @Test
    @DisplayName("A foreign-shard treasury number does not bypass API permissions")
    void foreignShardTreasuryNumberIsNotAuthorized() {
        // given a permission list that excludes the treasury and system-admin numbers:
        configProvider = () -> new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("createTopic", "1000-2000")
                        .getOrCreateConfig(),
                1);
        final var authorizer = new AuthorizerImpl(configProvider, privilegesVerifier);

        // the canonical treasury account still bypasses the permission list as a super-user:
        assertThat(authorizer.isAuthorized(accountId(0, 0, 2), hapiFunction)).isTrue();

        // but a foreign-shard id with the treasury number is neither a super-user nor permission-listed:
        assertThat(authorizer.isAuthorized(accountId(1, 0, 2), hapiFunction)).isFalse();
    }

    private static AccountID accountId(final long shard, final long realm, final long num) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(num)
                .build();
    }
}
