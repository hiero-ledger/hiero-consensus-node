// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.PermissionedAccountsRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

final class ApiPermissionConfigTest {

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {
                "NONE",
                "CRYPTO_ADD_LIVE_HASH",
                "CRYPTO_DELETE_LIVE_HASH",
                "GET_BY_SOLIDITY_ID",
                "GET_BY_KEY",
                "CRYPTO_GET_LIVE_HASH",
                "CRYPTO_GET_STAKERS",
                "CREATE_TRANSACTION_RECORD",
                "CRYPTO_ACCOUNT_AUTO_RENEW",
                "CONTRACT_AUTO_RENEW",
                "UNCHECKED_SUBMIT",
                "NODE_STAKE_UPDATE"
            })
    void testHederaFunctionalityUsage(final HederaFunctionality hederaFunctionality) {
        // given
        final var configuration = HederaTestConfigBuilder.create()
                .withConfigDataType(ApiPermissionConfig.class)
                .getOrCreateConfig();
        final ApiPermissionConfig config = configuration.getConfigData(ApiPermissionConfig.class);

        // when
        PermissionedAccountsRange permission = config.getPermission(hederaFunctionality);

        // then
        assertThat(permission).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            mode = Mode.INCLUDE,
            names = {
                "NONE",
                "CRYPTO_ADD_LIVE_HASH",
                "CRYPTO_DELETE_LIVE_HASH",
                "GET_BY_SOLIDITY_ID",
                "GET_BY_KEY",
                "CRYPTO_GET_LIVE_HASH",
                "CRYPTO_GET_STAKERS",
                "CREATE_TRANSACTION_RECORD",
                "CRYPTO_ACCOUNT_AUTO_RENEW",
                "CONTRACT_AUTO_RENEW",
                "UNCHECKED_SUBMIT",
                "NODE_STAKE_UPDATE"
            })
    void testNotSupportedHederaFunctionalityUsage(final HederaFunctionality hederaFunctionality) {
        // given
        final var configuration = HederaTestConfigBuilder.create()
                .withConfigDataType(ApiPermissionConfig.class)
                .getOrCreateConfig();
        final ApiPermissionConfig config = configuration.getConfigData(ApiPermissionConfig.class);

        // then
        assertThatThrownBy(() -> config.getPermission(hederaFunctionality))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
