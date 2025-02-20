// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;

final class SyntheticAccountsData {

    static final String GENESIS_KEY = "0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92";
    static final long EXPECTED_TREASURY_TINYBARS_BALANCE = 5000000000000000000L;
    static final int DEFAULT_NUM_SYSTEM_ACCOUNTS = 312;
    static final long EXPECTED_ENTITY_EXPIRY = 1812637686L;
    static final long TREASURY_ACCOUNT_NUM = 2L;
    static final long NUM_RESERVED_SYSTEM_ENTITIES = 750L;
    static final String EVM_ADDRESS_0 = "e261e26aecce52b3788fac9625896ffbc6bb4424";
    static final String EVM_ADDRESS_1 = "ce16e8eb8f4bf2e65ba9536c07e305b912bafacf";
    static final String EVM_ADDRESS_2 = "f39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    static final String EVM_ADDRESS_3 = "70997970c51812dc3a010c7d01b50e0d17dc79c8";
    static final String EVM_ADDRESS_4 = "7e5f4552091a69125d5dfcb7b8c2659029395bdf";
    static final String EVM_ADDRESS_5 = "a04a864273e77be6fe500ad2f5fad320d9168bb6";
    static final String[] EVM_ADDRESSES = {
        EVM_ADDRESS_0, EVM_ADDRESS_1, EVM_ADDRESS_2, EVM_ADDRESS_3, EVM_ADDRESS_4, EVM_ADDRESS_5
    };

    private SyntheticAccountsData() {
        throw new IllegalStateException("Data and utility class");
    }

    static Configuration buildConfig(final int numSystemAccounts, final boolean blocklistEnabled) {
        return configBuilder(numSystemAccounts, blocklistEnabled).getOrCreateConfig();
    }

    static TestConfigBuilder configBuilder(final int numSystemAccounts, final boolean blocklistEnabled) {
        return HederaTestConfigBuilder.create()
                // Accounts Config
                .withValue("accounts.treasury", TREASURY_ACCOUNT_NUM)
                .withValue("accounts.stakingRewardAccount", 800L)
                .withValue("accounts.nodeRewardAccount", 801L)
                .withValue("accounts.blocklist.enabled", blocklistEnabled)
                .withValue("accounts.blocklist.path", "blocklist-parsing/test-evm-addresses-blocklist.csv")
                // Bootstrap Config
                .withValue("bootstrap.genesisPublicKey", "0x" + GENESIS_KEY)
                .withValue("bootstrap.system.entityExpiry", EXPECTED_ENTITY_EXPIRY)
                // Hedera Config
                .withValue("hedera.realm", 0L)
                .withValue("hedera.shard", 0L)
                // Ledger Config
                .withValue("ledger.numSystemAccounts", numSystemAccounts)
                .withValue("ledger.numReservedSystemEntities", NUM_RESERVED_SYSTEM_ENTITIES)
                .withValue("ledger.totalTinyBarFloat", EXPECTED_TREASURY_TINYBARS_BALANCE);
    }
}
