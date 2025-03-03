// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.validation;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.AutoRenewConfig;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.junit.jupiter.api.Test;

class ExpiryValidationTest extends AppTestBase {

    private ExpiryValidation subject;

    @Test
    void testConstructorWithIllegalArguments() {
        assertThatThrownBy(() -> new ExpiryValidation(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testAccountWithPositiveBalanceSucceeds() {
        // given
        final ConfigProvider configProvider = createConfigProvider("ACCOUNT,CONTRACT");
        final var account = ALICE.account()
                .copyBuilder()
                .tinybarBalance(1L)
                .expiredAndPendingRemoval(true)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatCode(() -> subject.checkAccountExpiry(account)).doesNotThrowAnyException();
    }

    @Test
    void testAccountWithUnsetFlagSucceeds() {
        // given
        final ConfigProvider configProvider = createConfigProvider("ACCOUNT,CONTRACT");
        final var account = ALICE.account()
                .copyBuilder()
                .tinybarBalance(0L)
                .expiredAndPendingRemoval(false)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatCode(() -> subject.checkAccountExpiry(account)).doesNotThrowAnyException();
    }

    @Test
    void testExpiredAccountWithDisabledExpirySucceeds() {
        // given
        final ConfigProvider configProvider = createConfigProvider("CONTRACT");
        final var account = ALICE.account()
                .copyBuilder()
                .tinybarBalance(0L)
                .expiredAndPendingRemoval(true)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatCode(() -> subject.checkAccountExpiry(account)).doesNotThrowAnyException();
    }

    @Test
    void testExpiredAccountFails() {
        // given
        final ConfigProvider configProvider = createConfigProvider("ACCOUNT");
        final var account = ALICE.account()
                .copyBuilder()
                .tinybarBalance(0L)
                .expiredAndPendingRemoval(true)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatThrownBy(() -> subject.checkAccountExpiry(account))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void testExpiredContractWithDisabledExpirySucceeds() {
        // given
        final ConfigProvider configProvider = createConfigProvider("ACCOUNT");
        final var account = ALICE.account()
                .copyBuilder()
                .smartContract(true)
                .tinybarBalance(0L)
                .expiredAndPendingRemoval(true)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatCode(() -> subject.checkAccountExpiry(account)).doesNotThrowAnyException();
    }

    @Test
    void testExpiredContractFails() {
        // given
        final ConfigProvider configProvider = createConfigProvider("CONTRACT");
        final var account = ALICE.account()
                .copyBuilder()
                .smartContract(true)
                .tinybarBalance(0L)
                .expiredAndPendingRemoval(true)
                .build();
        final var subject = new ExpiryValidation(configProvider);

        // then
        assertThatThrownBy(() -> subject.checkAccountExpiry(account))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CONTRACT_EXPIRED_AND_PENDING_REMOVAL));
    }

    private static ConfigProvider createConfigProvider(final String autoRenewProperties) {
        final var config = new TestConfigBuilder(false)
                .withValue("autoRenew.targetTypes", autoRenewProperties)
                .withConfigDataType(AutoRenewConfig.class)
                .getOrCreateConfig();
        return () -> new VersionedConfigImpl(config, 1L);
    }
}
