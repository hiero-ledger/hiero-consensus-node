// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.validation;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hapi.utils.InvalidTransactionException;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpiryValidatorImplTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L);
    private static final long A_TIME = 666_666_666L;
    private static final long B_TIME = 777_777_777L;
    private static final long A_PERIOD = 666_666L;
    private static final long B_PERIOD = 777_777L;
    private static AccountID AN_AUTO_RENEW_ID;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock(strictness = LENIENT)
    private ReadableAccountStore accountStore;

    @Mock(strictness = LENIENT)
    private HandleContext context;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    private ExpiryValidatorImpl subject;

    @BeforeEach
    void setUp() {
        given(context.consensusNow()).willReturn(NOW);
        final var config = HederaTestConfigBuilder.createConfig();
        given(context.configuration()).willReturn(config);
        given(context.attributeValidator()).willReturn(attributeValidator);
        given(accountStore.getAccountById(any())).willReturn(Account.DEFAULT);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        long SHARD = config.getConfigData(HederaConfig.class).shard();
        long REALM = config.getConfigData(HederaConfig.class).realm();
        EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);
        AN_AUTO_RENEW_ID = idFactory.newAccountId(888);

        subject = new ExpiryValidatorImpl(context);
    }

    @Test
    void onCreationRequiresEitherExplicitValueOrFullAutoRenewMetaIfNotSelfFunding() {
        willThrow(new HandleException(INVALID_EXPIRATION_TIME))
                .given(attributeValidator)
                .validateExpiry(anyLong());
        final var expiryMeta1 = new ExpiryMeta(NA, NA, AN_AUTO_RENEW_ID);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta1, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
        final var expiryMeta2 = new ExpiryMeta(NA, A_PERIOD, null);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta2, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void validatesShard() {
        final var config =
                HederaTestConfigBuilder.create().withValue("hedera.shard", 1L).getOrCreateConfig();
        given(context.configuration()).willReturn(config);
        final var newMeta = new ExpiryMeta(
                A_TIME,
                A_PERIOD,
                AccountID.newBuilder().shardNum(2L).realmNum(2L).accountNum(888).build());

        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, newMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_AUTORENEW_ACCOUNT));
    }

    @Test
    void validatesRealm() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("hedera.shard", 1L)
                .withValue("hedera.realm", 2L)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);
        final var newMeta = new ExpiryMeta(
                A_TIME,
                A_PERIOD,
                AccountID.newBuilder().shardNum(1L).realmNum(3L).accountNum(888).build());

        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, newMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_AUTORENEW_ACCOUNT));
    }

    @Test
    void onCreationRequiresValidExpiryIfExplicit() {
        willThrow(new HandleException(INVALID_EXPIRATION_TIME))
                .given(attributeValidator)
                .validateExpiry(A_TIME);

        final var expiryMeta = new ExpiryMeta(A_TIME, NA, AN_AUTO_RENEW_ID);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void translatesFailureOnExplicitAutoRenewAccount() {
        given(accountStore.getAccountById(AN_AUTO_RENEW_ID))
                .willThrow(new InvalidTransactionException(
                        com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT));

        final var expiryMeta = new ExpiryMeta(A_TIME, NA, AN_AUTO_RENEW_ID);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_AUTORENEW_ACCOUNT));
    }

    @Test
    void onCreationUsesAutoRenewPeriodEvenWithoutFullSpecIfSelfFunding() {
        assertThatCode(() -> subject.resolveCreationAttempt(
                        true, new ExpiryMeta(NA, A_PERIOD, null), HederaFunctionality.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    void onCreationRequiresValidExpiryIfImplicit() {
        willThrow(new HandleException(INVALID_EXPIRATION_TIME))
                .given(attributeValidator)
                .validateExpiry(NOW.getEpochSecond() + A_PERIOD);

        final var expiryMeta = new ExpiryMeta(NA, A_PERIOD, AN_AUTO_RENEW_ID);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void validatesAutoRenewPeriodIfSet() {
        willThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .given(attributeValidator)
                .validateAutoRenewPeriod(A_PERIOD);

        final var expiryMeta = new ExpiryMeta(A_TIME, A_PERIOD, null);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void validatesImpliedExpiry() {
        willThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .given(attributeValidator)
                .validateAutoRenewPeriod(A_PERIOD);

        final var expiryMeta = new ExpiryMeta(A_TIME, A_PERIOD, null);
        assertThatThrownBy(() -> subject.resolveCreationAttempt(false, expiryMeta, HederaFunctionality.NONE))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void summarizesExpiryOnlyCase() {
        assertThatCode(() -> subject.resolveCreationAttempt(
                        false, new ExpiryMeta(A_TIME, NA, null), HederaFunctionality.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    void summarizesExpiryAndAutoRenewNumCase() {
        assertThatCode(() -> subject.resolveCreationAttempt(
                        false, new ExpiryMeta(A_TIME, NA, AN_AUTO_RENEW_ID), HederaFunctionality.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    void summarizesExpiryAndValidAutoRenewPeriodCase() {
        assertThatCode(() -> subject.resolveCreationAttempt(
                        false, new ExpiryMeta(A_TIME, A_PERIOD, null), HederaFunctionality.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    void summarizesFullAutoRenewSpecPeriodCase() {
        assertThatCode(() -> subject.resolveCreationAttempt(
                        false,
                        new ExpiryMeta(NOW.getEpochSecond() + A_PERIOD, A_PERIOD, AN_AUTO_RENEW_ID),
                        HederaFunctionality.NONE))
                .doesNotThrowAnyException();
    }

    @Test
    void updateCannotExplicitlyReduceExpiry() {
        final var current = new ExpiryMeta(A_TIME, NA, null);
        final var update = new ExpiryMeta(A_TIME - 1, NA, null);

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(EXPIRATION_REDUCTION_NOT_ALLOWED));
    }

    @Test
    void explicitExpiryExtensionMustBeValid() {
        final var current = new ExpiryMeta(A_TIME, NA, null);
        final var update = new ExpiryMeta(A_TIME - 1, NA, null);

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(EXPIRATION_REDUCTION_NOT_ALLOWED));
    }

    @Test
    void ifJustSettingAutoRenewAccountThenNetPeriodMustBeValid() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(NA, NA, AN_AUTO_RENEW_ID);

        willThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .given(attributeValidator)
                .validateAutoRenewPeriod(0L);

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void ifSettingAutoRenewPeriodThenMustBeValid() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(NA, B_PERIOD, AN_AUTO_RENEW_ID);

        willThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .given(attributeValidator)
                .validateAutoRenewPeriod(B_PERIOD);

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void ifUpdatingAutoRenewNumMustBeValid() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(NA, B_PERIOD, AN_AUTO_RENEW_ID);

        given(accountStore.getAccountById(AN_AUTO_RENEW_ID))
                .willThrow(new InvalidTransactionException(
                        com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT));

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_AUTORENEW_ACCOUNT));
    }

    @Test
    void ifUpdatingExpiryMustBeValid() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(B_TIME, B_PERIOD, AN_AUTO_RENEW_ID);

        willThrow(new HandleException(INVALID_EXPIRATION_TIME))
                .given(attributeValidator)
                .validateExpiry(B_TIME);

        assertThatThrownBy(() -> subject.resolveUpdateAttempt(current, update))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void canSetEverythingValidly() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(B_TIME, B_PERIOD, AN_AUTO_RENEW_ID);

        assertThat(subject.resolveUpdateAttempt(current, update)).isEqualTo(update);
    }

    @Test
    void canUseWildcardForRemovingAutoRenewAccount() {
        final var current = new ExpiryMeta(A_TIME, 0, null);
        final var update = new ExpiryMeta(
                B_TIME,
                B_PERIOD,
                AccountID.newBuilder().shardNum(0L).realmNum(0L).accountNum(0L).build());

        assertThat(subject.resolveUpdateAttempt(current, update)).isEqualTo(update);
    }

    @Test
    void checksIfAccountIsDetachedIfBalanceZero() {
        assertThat(subject.expirationStatus(EntityType.ACCOUNT, false, 0)).isEqualTo(OK);
        assertThat(subject.isDetached(EntityType.ACCOUNT, false, 0)).isFalse();
    }

    @Test
    void failsIfAccountExpiredAndPendingRemoval() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoRenew.targetTypes", "CONTRACT,ACCOUNT")
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        assertThat(subject.expirationStatus(EntityType.ACCOUNT, true, 0L))
                .isEqualTo(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
        assertThat(subject.isDetached(EntityType.ACCOUNT, true, 0)).isTrue();

        assertThat(subject.expirationStatus(EntityType.CONTRACT_BYTECODE, true, 0L))
                .isEqualTo(CONTRACT_EXPIRED_AND_PENDING_REMOVAL);
        assertThat(subject.isDetached(EntityType.CONTRACT_BYTECODE, true, 0)).isTrue();
    }

    @Test
    void notDetachedIfAccountNotExpired() {
        assertThat(subject.expirationStatus(EntityType.ACCOUNT, false, 0L)).isEqualTo(OK);
        assertThat(subject.isDetached(EntityType.ACCOUNT, false, 10)).isFalse();
    }

    @Test
    void notDetachedIfAutoRenewDisabled() {
        assertThat(subject.expirationStatus(EntityType.ACCOUNT, false, 0L)).isEqualTo(OK);
        assertThat(subject.isDetached(EntityType.ACCOUNT, false, 0)).isFalse();
    }
}
