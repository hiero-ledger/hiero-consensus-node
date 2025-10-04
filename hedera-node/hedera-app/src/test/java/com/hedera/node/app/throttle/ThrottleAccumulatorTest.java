// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.GET_VERSION_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.NETWORK_GET_EXECUTION_TIME;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TRANSACTION_GET_RECEIPT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_LABEL;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.NOOP_THROTTLE;
import static com.hedera.pbj.runtime.ProtoTestTools.getThreadLocalDataBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceQuery;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.app.hapi.utils.throttles.BucketThrottle;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType;
import com.hedera.node.app.throttle.ThrottleAccumulator.Verbose;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class ThrottleAccumulatorTest {

    private static final int CAPACITY_SPLIT = 2;
    private static final Instant TIME_INSTANT = Instant.ofEpochSecond(1_234_567L, 123);
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1234L).build();
    private static final AccountID RECEIVER_ID =
            AccountID.newBuilder().accountNum(1256L).build();
    private static final TokenID TOKEN_ID = TokenID.newBuilder().tokenNum(3333L).build();
    private static final Key A_PRIMITIVE_KEY = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final ScheduleID SCHEDULE_ID =
            ScheduleID.newBuilder().scheduleNum(333333L).build();
    private static final ScaleFactor NFT_SCALE_FACTOR = ScaleFactor.from("5:2");
    private static final Bytes ETH_LAZY_CREATE = Bytes.fromHex(
            "02f8ad82012a80a000000000000000000000000000000000000000000000000000000000000003e8a0000000000000000000000000000000000000000000000000000000746a528800831e848094fee687d5088faff48013a6767505c027e2742536880de0b6b3a764000080c080a0f5ddf2394311e634e2147bf38583a017af45f4326bdf5746cac3a1110f973e4fa025bad52d9a9f8b32eb983c9fb8959655258bd75e2826b2c6a48d4c26ec30d112");

    @LoggingSubject
    private ThrottleAccumulator subject;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private LeakyBucketDeterministicThrottle gasThrottle;

    @Mock
    private LeakyBucketDeterministicThrottle bytesThrottle;

    @Mock
    private OpsDurationDeterministicThrottle opsDurationThrottle;

    @Mock
    private ThrottleMetrics throttleMetrics;

    @Mock
    private VersionedConfiguration configuration;

    @Mock
    private SchedulingConfig schedulingConfig;

    @Mock
    private AccountsConfig accountsConfig;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private JumboTransactionsConfig jumboTransactionsConfig;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private EntitiesConfig entitiesConfig;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableKVState aliases;

    @Mock
    private ReadableKVState schedules;

    @Mock
    private ReadableKVState tokenRels;

    @Mock
    private Query query;

    @Mock
    private TransactionInfo transactionInfo;

    private SemanticVersion softwareVersionFactory = SemanticVersion.DEFAULT;
    private final HederaConfig hederaConfig =
            HederaTestConfigBuilder.create().getOrCreateConfig().getConfigData(HederaConfig.class);

    @Test
    void noOpThrottlesNeverThrottleAnything() throws PreCheckException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                NOOP_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        assertFalse(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
        assertFalse(subject.checkAndEnforceThrottle(
                TRANSACTION_GET_RECEIPT, TIME_INSTANT, query, state, AccountID.DEFAULT));
        assertFalse(subject.shouldThrottleNOfUnscaled(1, CRYPTO_TRANSFER, TIME_INSTANT));
        assertDoesNotThrow(() -> subject.leakCapacityForNOfUnscaled(1, CRYPTO_TRANSFER));
        assertDoesNotThrow(() -> subject.leakUnusedGasPreviouslyReserved(transactionInfo, 1L));
    }

    @Test
    void worksAsExpectedForKnownQueries() throws IOException, ParseException, PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);

        final var defs = getThrottleDefs("bootstrap/throttles.json");
        subject.rebuildFor(defs);

        // when
        final var queryPayerId = AccountID.newBuilder().accountNum(1_234L).build();
        var noAns = subject.checkAndEnforceThrottle(TRANSACTION_GET_RECEIPT, TIME_INSTANT, query, state, queryPayerId);
        subject.checkAndEnforceThrottle(GET_VERSION_INFO, TIME_INSTANT.plusNanos(1), query, state, queryPayerId);
        final var yesAns = subject.checkAndEnforceThrottle(
                GET_VERSION_INFO, TIME_INSTANT.plusNanos(2), query, state, queryPayerId);
        final var throttlesNow = subject.activeThrottlesFor(TRANSACTION_GET_RECEIPT);
        final var dNow = throttlesNow.getFirst();

        // then
        assertFalse(noAns);
        assertTrue(yesAns);
        assertEquals(10999999990000L, dNow.used());
    }

    @Test
    void worksAsExpectedForSimpleGetBalanceThrottle() throws IOException, ParseException, PreCheckException {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.countingGetBalanceThrottleEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");
        subject.rebuildFor(defs);
        final var query = Query.newBuilder()
                .cryptogetAccountBalance(CryptoGetAccountBalanceQuery.newBuilder()
                        .accountID(RECEIVER_ID)
                        .build())
                .build();
        final var account = Account.newBuilder().numberAssociations(2).build();
        final var states = MapReadableStates.builder()
                .state(new MapReadableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, Map.of(RECEIVER_ID, account)))
                .state(new MapReadableKVState<>(ALIASES_STATE_ID, ALIASES_STATE_LABEL, Map.of()))
                .build();
        lenient().when(state.getReadableStates(TokenService.NAME)).thenReturn(states);

        // when
        final var result = new boolean[] {
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(0), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(1), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(2), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(3), query, state, PAYER_ID)
        };

        // then
        assertThat(result).containsExactly(false, false, false, true);
    }

    @Test
    void worksAsExpectedForCountingGetBalanceThrottle() throws IOException, ParseException {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.countingGetBalanceThrottleEnabled", true)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");
        subject.rebuildFor(defs);
        final var query = Query.newBuilder()
                .cryptogetAccountBalance(CryptoGetAccountBalanceQuery.newBuilder()
                        .accountID(RECEIVER_ID)
                        .build())
                .build();
        final var account = Account.newBuilder().numberAssociations(2).build();
        final var states = MapReadableStates.builder()
                .state(new MapReadableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, Map.of(RECEIVER_ID, account)))
                .state(new MapReadableKVState<>(ALIASES_STATE_ID, ALIASES_STATE_LABEL, Map.of()))
                .build();
        given(state.getReadableStates(TokenService.NAME)).willReturn(states);
        final var entityIdStates = MapReadableStates.builder()
                .state(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()))
                .state(new FunctionReadableSingletonState<Object>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.newBuilder()
                                .build()))
                .build();
        given(state.getReadableStates(EntityIdService.NAME)).willReturn(entityIdStates);

        // when
        final var result = new boolean[] {
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(0), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(1), query, state, PAYER_ID)
        };

        // then
        assertThat(result).containsExactly(false, true);
    }

    @Test
    void worksAsExpectedForCountingGetBalanceThrottleWithEmptyAccount() throws IOException, ParseException {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("tokens.countingGetBalanceThrottleEnabled", true)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");
        subject.rebuildFor(defs);
        final var query = Query.newBuilder()
                .cryptogetAccountBalance(CryptoGetAccountBalanceQuery.newBuilder()
                        .accountID(RECEIVER_ID)
                        .build())
                .build();
        final var account = Account.newBuilder().numberAssociations(0).build();
        final var states = MapReadableStates.builder()
                .state(new MapReadableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, Map.of(RECEIVER_ID, account)))
                .state(new MapReadableKVState<>(ALIASES_STATE_ID, ALIASES_STATE_LABEL, Map.of()))
                .build();
        given(state.getReadableStates(TokenService.NAME)).willReturn(states);
        final var entityIdStates = MapReadableStates.builder()
                .state(new FunctionReadableSingletonState<Object>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityCounts.newBuilder()
                                .build()))
                .state(new FunctionReadableSingletonState<Object>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()))
                .build();
        given(state.getReadableStates(EntityIdService.NAME)).willReturn(entityIdStates);

        // when
        final var result = new boolean[] {
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(0), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(1), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(2), query, state, PAYER_ID),
            subject.checkAndEnforceThrottle(
                    CRYPTO_GET_ACCOUNT_BALANCE, TIME_INSTANT.plusNanos(3), query, state, PAYER_ID)
        };

        // then
        assertThat(result).containsExactly(false, false, false, true);
    }

    @Test
    void worksAsExpectedForUnknownQueries() throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        // when
        subject.rebuildFor(defs);

        // then
        final var queryPayerId = AccountID.newBuilder().accountNum(1_234L).build();
        assertTrue(
                subject.checkAndEnforceThrottle(NETWORK_GET_EXECUTION_TIME, TIME_INSTANT, query, state, queryPayerId));
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void checkAndClaimThrottlesByGasAndTotalAllowedGasPerSecNotSetOrZero(
            ThrottleAccumulator.ThrottleType throttleType) {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);

        // when
        subject.applyGasConfig();

        // then
        System.out.println(logCaptor.warnLogs());
        assertThat(logCaptor.warnLogs()).contains(throttleType + " gas throttling enabled, but limited to 0 gas/sec");
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerBehavesAsExpectedForFungibleMint(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        final int numNfts = 0;
        givenMintWith(numNfts);

        // when
        subject.rebuildFor(defs);
        // and
        var firstAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);
        var subsequentAns = ThrottleResult.allowed();
        for (int i = 1; i <= 3000; i++) {
            subsequentAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusNanos(i), state, null);
        }
        var throttlesNow = subject.activeThrottlesFor(TOKEN_MINT);
        var aNow = throttlesNow.get(0);

        // then
        assertFalse(firstAns.shouldThrottle());
        assertTrue(subsequentAns.shouldThrottle());
        assertEquals(29999955000000000L, aNow.used());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerBehavesAsExpectedForNftMint(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        final var numNfts = 3;
        givenMintWith(numNfts);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.nftsMintThrottleScaleFactor()).willReturn(NFT_SCALE_FACTOR);

        // when
        subject.rebuildFor(defs);
        // and
        var firstAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);
        var subsequentAns = ThrottleResult.allowed();
        for (int i = 1; i <= 400; i++) {
            subsequentAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusNanos(i), state, null);
        }
        var throttlesNow = subject.activeThrottlesFor(TOKEN_MINT);
        // and
        var aNow = throttlesNow.get(0);

        // then
        assertFalse(firstAns.shouldThrottle());
        assertTrue(subsequentAns.shouldThrottle());
        assertEquals(29999994000000000L, aNow.used());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerBehavesAsExpectedForMultiBucketOp(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CONTRACT_CALL);

        // when
        subject.rebuildFor(defs);
        // and
        var firstAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);
        var subsequentAns = ThrottleResult.allowed();
        for (int i = 1; i <= 12; i++) {
            subsequentAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusNanos(i), state, null);
        }
        var throttlesNow = subject.activeThrottlesFor(CONTRACT_CALL);
        // and
        var aNow = throttlesNow.get(0);
        var bNow = throttlesNow.get(1);

        // then
        assertFalse(firstAns.shouldThrottle());
        assertTrue(subsequentAns.shouldThrottle());
        assertEquals(24999999820000000L, aNow.used());
        assertEquals(9999999940000L, bNow.used());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void handlesThrottleExemption(ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);

        final var defs = getThrottleDefs("bootstrap/throttles.json");
        given(transactionInfo.functionality()).willReturn(CONTRACT_CALL);
        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1L).build());

        // when:
        subject.rebuildFor(defs);
        // and:
        var firstAns = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);
        for (int i = 1; i <= 12; i++) {
            assertFalse(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusNanos(i), state, null)
                    .shouldThrottle());
        }
        var throttlesNow = subject.activeThrottlesFor(CONTRACT_CALL);
        // and:
        var aNow = throttlesNow.get(0);
        var bNow = throttlesNow.get(1);

        // then:
        assertFalse(firstAns.shouldThrottle());
        assertEquals(0, aNow.used());
        assertEquals(0, bNow.used());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void computesNumImplicitCreationsIfNotAlreadyKnown(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);
        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        final var txn = TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                .build();
        given(transactionInfo.txBody()).willReturn(txn);
        given(state.getReadableStates(any())).willReturn(readableStates);

        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void ifLazyCreationEnabledComputesNumImplicitCreationsIfNotAlreadyKnown(
            ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException, PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        final var txn = TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                .build();
        given(transactionInfo.txBody()).willReturn(txn);

        given(state.getReadableStates(any())).willReturn(readableStates);

        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void cryptoTransfersWithNoAutoAccountCreationsAreThrottledAsExpected(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        given(jumboTransactionsConfig.allowedHederaFunctionalities()).willReturn(Set.of(fromPbj(ETHEREUM_TRANSACTION)));

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        final int numImplicitCreations = 0;
        givenTransferWithImplicitCreations(numImplicitCreations);

        given(state.getReadableStates(any())).willReturn(readableStates);

        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerAllowsCryptoTransfersWithAutoAccountCreationsAsExpected(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        given(jumboTransactionsConfig.allowedHederaFunctionalities()).willReturn(Set.of(fromPbj(ETHEREUM_TRANSACTION)));

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        final int numImplicitCreations = 1;
        givenTransferWithImplicitCreations(numImplicitCreations);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);
        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerAllowsCryptoTransfersWithAutoAssociationsAsExpected(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        final int numImplicitCreations = 1;
        givenTransferWithAutoAssociations(numImplicitCreations);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(tokenRels);

        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerRejectsCryptoTransfersWithAutoAccountCreationsAsExpected(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        givenTransferWithImplicitCreations(10);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);
        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertTrue(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerRejectsCryptoTransfersWithAutoAssociationsAsExpected(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        givenTransferWithAutoAssociations(101);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(tokenRels);

        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertTrue(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerRejectsCryptoTransfersWithMissingCryptoCreateThrottle(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        given(jumboTransactionsConfig.allowedHederaFunctionalities()).willReturn(Set.of(fromPbj(ETHEREUM_TRANSACTION)));

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles-sans-creation.json");

        given(transactionInfo.functionality()).willReturn(CRYPTO_TRANSFER);
        givenTransferWithImplicitCreations(1);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);
        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertTrue(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void ethereumTransactionWithNoAutoAccountCreationsAreThrottledAsExpected(
            ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException, PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        final var ethTxnBody = EthereumTransactionBody.newBuilder()
                .ethereumData(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(ethTxnBody)
                        .build());

        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void ethereumTransactionWithAutoAccountCreationsButNoLazyCreationsAreThrottledAsExpected(
            ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        final var ethTxnBody = EthereumTransactionBody.newBuilder()
                .ethereumData(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(ethTxnBody)
                        .build());

        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerAllowsEthereumTransactionWithAutoAccountCreationsAsExpected(
            ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException, PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles.json");

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        final var ethTxnBody = EthereumTransactionBody.newBuilder()
                .ethereumData(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(ethTxnBody)
                        .build());

        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertFalse(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void managerRejectsEthereumTransactionWithMissingCryptoCreateThrottle(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var defs = getThrottleDefs("bootstrap/throttles-sans-creation.json");

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        final var ethTxnBody = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_LAZY_CREATE)
                .build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(ethTxnBody)
                        .build());

        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(anyInt())).willReturn(aliases);

        // when
        subject.rebuildFor(defs);
        var ans = subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null);

        // then
        assertTrue(ans.shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesContractCallWhenGasThrottleIsNotDefined(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 0L);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(CONTRACT_CALL);
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .contractCall(ContractCallTransactionBody.DEFAULT)
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesContractCallWhenGasThrottleReturnsTrue(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 1L);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(CONTRACT_CALL);
        final var contractCallTxnBody =
                ContractCallTransactionBody.newBuilder().gas(2L).build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .contractCall(contractCallTxnBody)
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesContractCreateWhenGasThrottleIsNotDefined(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 0L);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        given(jumboTransactionsConfig.allowedHederaFunctionalities()).willReturn(Set.of(fromPbj(ETHEREUM_TRANSACTION)));

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(CONTRACT_CREATE);
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .contractCreateInstance(ContractCreateTransactionBody.DEFAULT)
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesContractCreateWhenGasThrottleReturnsTrue(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 1L);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(CONTRACT_CREATE);
        final var contractCreateTxnBody =
                ContractCreateTransactionBody.newBuilder().gas(2L).build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .contractCreateInstance(contractCreateTxnBody)
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
        assertTrue(subject.wasLastTxnGasThrottled());

        given(transactionInfo.functionality()).willReturn(TOKEN_BURN);
        subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusSeconds(1), state, null);
        assertFalse(subject.wasLastTxnGasThrottled());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesEthereumTxnWhenGasThrottleIsNotDefined(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 0L);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(EthereumTransactionBody.newBuilder()
                                .ethereumData(Bytes.wrap(new byte[] {1, 2, 3}))
                                .build())
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysThrottlesEthereumTxnWhenGasThrottleReturnsTrue(ThrottleAccumulator.ThrottleType throttleType)
            throws PreCheckException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 1L);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(ETHEREUM_TRANSACTION);
        final var ethTxnBody = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_LAZY_CREATE)
                .build();
        given(transactionInfo.txBody())
                .willReturn(TransactionBody.newBuilder()
                        .ethereumTransaction(ethTxnBody)
                        .build());

        // when
        subject.applyGasConfig();
        subject.applyBytesConfig();
        subject.applyDurationConfig();

        // then
        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
        assertTrue(subject.wasLastTxnGasThrottled());

        given(transactionInfo.functionality()).willReturn(TOKEN_BURN);
        subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT.plusSeconds(1), state, null);
        assertFalse(subject.wasLastTxnGasThrottled());
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void gasLimitThrottleReturnsCorrectObject(ThrottleAccumulator.ThrottleType throttleType) {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        var capacity = 10L;
        setMaxGasAndBurst(throttleType, capacity);

        // when
        subject.applyGasConfig();

        // then
        assertEquals(capacity, subject.gasLimitThrottle().capacity());
        verify(throttleMetrics).setupGasThrottleMetric(subject.gasLimitThrottle(), configuration);
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void bytesLimitThrottleReturnsCorrectObject(ThrottleAccumulator.ThrottleType throttleType) {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        var capacity = 10L;
        given(jumboTransactionsConfig.maxBytesPerSec()).willReturn(capacity);

        // when
        subject.applyBytesConfig();

        // then
        assertEquals(capacity, subject.bytesLimitThrottle().capacity());
        verify(throttleMetrics).setupBytesThrottleMetric(subject.bytesLimitThrottle(), configuration);
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void constructsExpectedBucketsFromTestResource(ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        final var defs = getThrottleDefs("bootstrap/throttles.json");

        // and
        var expected = List.of(
                DeterministicThrottle.withMtpsAndBurstPeriod(15_000_000, 2),
                DeterministicThrottle.withMtpsAndBurstPeriod(5_000, 2),
                DeterministicThrottle.withMtpsAndBurstPeriod(50_000, 3),
                DeterministicThrottle.withMtpsAndBurstPeriod(5000, 4),
                DeterministicThrottle.withMtpsAndBurstPeriod(3000, 1));

        // when
        subject.rebuildFor(defs);
        // and
        var rebuilt = subject.allActiveThrottles();

        // then
        assertEquals(expected, rebuilt);
        verify(throttleMetrics).setupThrottleMetrics(rebuilt, configuration);
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void alwaysRejectsIfNoThrottle(ThrottleAccumulator.ThrottleType throttleType) throws PreCheckException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);
        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        given(transactionInfo.functionality()).willReturn(CONTRACT_CALL);

        assertTrue(subject.checkAndEnforceThrottle(transactionInfo, TIME_INSTANT, state, null)
                .shouldThrottle());
        Assertions.assertSame(Collections.emptyList(), subject.activeThrottlesFor(CONTRACT_CALL));
    }

    @ParameterizedTest
    @EnumSource(value = ThrottleAccumulator.ThrottleType.class, mode = EnumSource.Mode.EXCLUDE, names = "NOOP_THROTTLE")
    void verifyLeakUnusedGas(ThrottleAccumulator.ThrottleType throttleType) throws IOException, ParseException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT, configProvider::getConfiguration, throttleType, throttleMetrics, Verbose.YES);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(true);
        setMaxGasAndBurst(throttleType, 150L);

        // payer is not exempt
        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        subject.applyGasConfig();

        assertTrue(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));
        assertFalse(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));

        subject.leakUnusedGasPreviouslyReserved(transactionInfo, 100L);

        assertTrue(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));
        assertFalse(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));

        // payer is exempt
        given(transactionInfo.payerID())
                .willReturn(AccountID.newBuilder().accountNum(1L).build());

        subject.leakUnusedGasPreviouslyReserved(transactionInfo, 100L);

        assertFalse(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));
        assertFalse(subject.gasLimitThrottle().allow(TIME_INSTANT, 100));
    }

    private void setMaxGasAndBurst(ThrottleType throttleType, long t) {
        if (throttleType.equals(FRONTEND_THROTTLE)) {
            given(contractsConfig.maxGasPerSec()).willReturn(t);
        } else {
            given(contractsConfig.maxGasPerSecBackend()).willReturn(t);
        }
    }

    @Test
    void alwaysThrottleNOfUnmanaged() throws IOException, ParseException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");

        subject.rebuildFor(defs);

        assertTrue(subject.shouldThrottleNOfUnscaled(2, TOKEN_BURN, TIME_INSTANT));
    }

    @Test
    void canThrottleNOfManaged() throws IOException, ParseException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");

        subject.rebuildFor(defs);

        assertFalse(subject.shouldThrottleNOfUnscaled(1, TOKEN_MINT, TIME_INSTANT));
        final var oneUsed = subject.activeThrottlesFor(TOKEN_MINT).get(0).used();
        assertFalse(subject.shouldThrottleNOfUnscaled(41, TOKEN_MINT, TIME_INSTANT));
        final var fortyTwoUsed = subject.activeThrottlesFor(TOKEN_MINT).get(0).used();
        assertEquals(42 * oneUsed, fortyTwoUsed);
    }

    @Test
    void whenThrottlesUsesNoCapacity() throws IOException, ParseException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");

        subject.rebuildFor(defs);

        assertTrue(subject.shouldThrottleNOfUnscaled(11, CONTRACT_CALL, TIME_INSTANT));
        final var used = subject.activeThrottlesFor(CONTRACT_CALL).get(0).used();
        assertEquals(0, used);
    }

    @Test
    void canLeakCapacityForNOfManaged() throws IOException, ParseException {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        final var defs = getThrottleDefs("bootstrap/throttles.json");

        subject.rebuildFor(defs);

        subject.shouldThrottleNOfUnscaled(1, TOKEN_MINT, TIME_INSTANT);
        final var oneUsed = subject.activeThrottlesFor(TOKEN_MINT).get(0).used();
        subject.shouldThrottleNOfUnscaled(43, TOKEN_MINT, TIME_INSTANT);
        subject.leakCapacityForNOfUnscaled(2, TOKEN_MINT);
        final var fortyTwoUsed = subject.activeThrottlesFor(TOKEN_MINT).get(0).used();
        assertEquals(42 * oneUsed, fortyTwoUsed);
    }

    @ParameterizedTest
    @CsvSource({
        "FRONTEND_THROTTLE,true,true",
        "FRONTEND_THROTTLE,true,false",
        "FRONTEND_THROTTLE,false,true",
        "FRONTEND_THROTTLE,false,false",
        "BACKEND_THROTTLE,true,true",
        "BACKEND_THROTTLE,true,false",
        "BACKEND_THROTTLE,false,true",
        "BACKEND_THROTTLE,false,false",
    })
    void usesScheduleSignThrottle(final ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);

        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        final var defs = getThrottleDefs("bootstrap/schedule-create-throttles.json");
        subject.rebuildFor(defs);

        // when
        final var txnInfo = scheduleSign(SCHEDULE_ID);
        final var firstAns = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        var subsequentAns = ThrottleResult.allowed();
        for (int i = 1; i <= 150; i++) {
            subsequentAns = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT.plusNanos(i), state, null);
        }

        final var throttlesNow = subject.activeThrottlesFor(SCHEDULE_SIGN);
        final var aNow = throttlesNow.getFirst();

        // then
        assertFalse(firstAns.shouldThrottle());
        assertTrue(subsequentAns.shouldThrottle());
        assertEquals(149999992500000L, aNow.used());

        assertEquals(
                0,
                subject.activeThrottlesFor(CONSENSUS_SUBMIT_MESSAGE).getFirst().used());
    }

    @ParameterizedTest
    @CsvSource({
        "FRONTEND_THROTTLE,true,true",
        "FRONTEND_THROTTLE,true,false",
        "FRONTEND_THROTTLE,false,true",
        "FRONTEND_THROTTLE,false,false",
        "BACKEND_THROTTLE,true,true",
        "BACKEND_THROTTLE,true,false",
        "BACKEND_THROTTLE,false,true",
        "BACKEND_THROTTLE,false,false",
    })
    void usesScheduleSignThrottleWithNestedThrottleExempt(final ThrottleAccumulator.ThrottleType throttleType)
            throws IOException, ParseException {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);

        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(false);

        final var defs = getThrottleDefs("bootstrap/schedule-create-throttles.json");
        subject.rebuildFor(defs);

        // when
        final var txnInfo = scheduleSign(SCHEDULE_ID);
        final var firstAns = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        var subsequentAns = ThrottleResult.allowed();
        for (int i = 1; i <= 150; i++) {
            subsequentAns = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT.plusNanos(i), state, null);
        }

        final var throttlesNow = subject.activeThrottlesFor(SCHEDULE_SIGN);
        final var aNow = throttlesNow.getFirst();

        // then
        assertFalse(firstAns.shouldThrottle());
        assertTrue(subsequentAns.shouldThrottle());
        assertEquals(149999992500000L, aNow.used());

        assertEquals(
                0,
                subject.activeThrottlesFor(CONSENSUS_SUBMIT_MESSAGE).getFirst().used());
    }

    @ParameterizedTest
    @CsvSource({
        "FRONTEND_THROTTLE,true",
        "FRONTEND_THROTTLE,false",
        "BACKEND_THROTTLE,true",
        "BACKEND_THROTTLE,false",
    })
    @MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void usesCryptoCreateThrottleForCryptoTransferWithAutoCreationInScheduleSign(
            final ThrottleAccumulator.ThrottleType throttleType, final boolean longTermEnabled)
            throws IOException, ParseException {

        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                throttleType,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);

        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
        given(accountsConfig.lastThrottleExempt()).willReturn(100L);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
        given(contractsConfig.throttleThrottleByGas()).willReturn(false);
        given(configuration.getConfigData(SchedulingConfig.class)).willReturn(schedulingConfig);
        given(schedulingConfig.longTermEnabled()).willReturn(longTermEnabled);
        given(configuration.getConfigData(EntitiesConfig.class)).willReturn(entitiesConfig);
        given(entitiesConfig.unlimitedAutoAssociationsEnabled()).willReturn(true);
        given(configuration.getConfigData(JumboTransactionsConfig.class)).willReturn(jumboTransactionsConfig);
        given(jumboTransactionsConfig.isEnabled()).willReturn(true);
        given(jumboTransactionsConfig.allowedHederaFunctionalities()).willReturn(Set.of(fromPbj(ETHEREUM_TRANSACTION)));

        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(ALIASES_STATE_ID)).willReturn(aliases);

        final var alias = keyToBytes(A_PRIMITIVE_KEY);
        if (throttleType == FRONTEND_THROTTLE && longTermEnabled) {
            given(aliases.get(any())).willReturn(null);
        }

        if (longTermEnabled && throttleType == FRONTEND_THROTTLE) {
            var accountAmounts = new ArrayList<AccountAmount>();
            accountAmounts.add(AccountAmount.newBuilder()
                    .amount(-1_000_000_000L)
                    .accountID(AccountID.newBuilder().accountNum(3333L).build())
                    .build());
            accountAmounts.add(AccountAmount.newBuilder()
                    .amount(+1_000_000_000L)
                    .accountID(AccountID.newBuilder().alias(alias).build())
                    .build());
            final var scheduledTransferWithAutoCreation = SchedulableTransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                            .transfers(TransferList.newBuilder()
                                    .accountAmounts(accountAmounts)
                                    .build()))
                    .build();

            final var scheduleCreateTxnInfo = scheduleCreate(scheduledTransferWithAutoCreation, false, null);
            final var schedule = Schedule.newBuilder()
                    .waitForExpiry(
                            scheduleCreateTxnInfo.txBody().scheduleCreate().waitForExpiry())
                    .originalCreateTransaction(scheduleCreateTxnInfo.txBody())
                    .payerAccountId(scheduleCreateTxnInfo.payerID())
                    .scheduledTransaction(scheduledTransferWithAutoCreation)
                    .build();
            given(readableStates.get(SCHEDULES_BY_ID_STATE_ID)).willReturn(schedules);
            given(schedules.get(SCHEDULE_ID)).willReturn(schedule);
        }

        final var defs = getThrottleDefs("bootstrap/schedule-create-throttles.json");
        subject.rebuildFor(defs);

        // when
        final var scheduleSignTxnInfo = scheduleSign(SCHEDULE_ID);
        final var ans = subject.checkAndEnforceThrottle(scheduleSignTxnInfo, TIME_INSTANT, state, null);
        final var throttlesNow = subject.activeThrottlesFor(SCHEDULE_SIGN);
        final var aNow = throttlesNow.getFirst();

        // then
        assertFalse(ans.shouldThrottle());
        assertEquals(BucketThrottle.capacityUnitsPerTxn(), aNow.used());
        assertEquals(0, subject.activeThrottlesFor(CRYPTO_TRANSFER).getFirst().used());
    }

    @Test
    void scheduleCreateWithMissingScheduledTransactionBodyThrowsException() throws Exception {
        // Given
        setupThrottleSubject();
        final var scheduleCreate = ScheduleCreateTransactionBody.newBuilder()
                .waitForExpiry(false)
                .build(); // Missing scheduledTransactionBody

        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .scheduleCreate(scheduleCreate)
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();

        final var txnInfo = new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                SCHEDULE_CREATE,
                null);

        // When & Then - should return ThrottleResult with validation error for invalid transaction
        final var result = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        assertTrue(result.hasValidationError());
        assertEquals(INVALID_TRANSACTION_BODY, result.validationError());
    }

    @Test
    void tokenMintWithMissingTokenThrowsException() throws Exception {
        // Given
        setupThrottleSubject();
        final var tokenMint = TokenMintTransactionBody.newBuilder().amount(100L).build(); // Missing token field

        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .tokenMint(tokenMint)
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();

        final var txnInfo = new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                TOKEN_MINT,
                null);

        // When & Then - should return ThrottleResult with validation error
        final var result = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        assertTrue(result.hasValidationError());
        assertEquals(ResponseCodeEnum.INVALID_TRANSACTION_BODY, result.validationError());
    }

    @Test
    void ethereumTransactionWithEmptyDataThrowsException() throws Exception {
        // Given
        setupThrottleSubject();
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(Bytes.EMPTY) // Empty ethereum data
                .build();

        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .ethereumTransaction(ethTxn)
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();

        final var txnInfo = new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                ETHEREUM_TRANSACTION,
                null);

        // When & Then - should return ThrottleResult with validation error
        final var result = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        assertTrue(result.hasValidationError());
        assertEquals(ResponseCodeEnum.INVALID_TRANSACTION_BODY, result.validationError());
    }

    @Test
    void ethereumTransactionWithNullDataThrowsException() throws Exception {
        // Given
        setupThrottleSubject();
        final var ethTxn = EthereumTransactionBody.newBuilder().build(); // No ethereum data set (null)

        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .ethereumTransaction(ethTxn)
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();

        final var txnInfo = new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                ETHEREUM_TRANSACTION,
                null);

        // When & Then - should return ThrottleResult with validation error
        final var result = subject.checkAndEnforceThrottle(txnInfo, TIME_INSTANT, state, null);
        assertTrue(result.hasValidationError());
        assertEquals(ResponseCodeEnum.INVALID_TRANSACTION_BODY, result.validationError());
    }

    @Test
    void updateMetrics() {
        // given
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                Verbose.YES);

        // when
        subject.updateAllMetrics();

        // then
        verify(throttleMetrics).updateAllMetrics();
    }

    @NonNull
    private static Bytes keyToBytes(Key key) throws IOException, ParseException {
        final var dataBuffer = getThreadLocalDataBuffer();
        Key.PROTOBUF.write(key, dataBuffer);
        // clamp limit to bytes written
        dataBuffer.limit(dataBuffer.position());
        return dataBuffer.getBytes(0, dataBuffer.length());
    }

    private TransactionInfo scheduleCreate(
            final SchedulableTransactionBody inner, boolean waitForExpiry, AccountID customPayer) {
        final var schedule = ScheduleCreateTransactionBody.newBuilder()
                .waitForExpiry(waitForExpiry)
                .scheduledTransactionBody(inner);
        if (customPayer != null) {
            schedule.payerAccountID(customPayer);
        }
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .scheduleCreate(schedule)
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();
        return new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                SCHEDULE_CREATE,
                null);
    }

    private TransactionInfo scheduleSign(ScheduleID scheduleID) {
        final var schedule = ScheduleSignTransactionBody.newBuilder().scheduleID(scheduleID);
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .scheduleSign(schedule)
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();
        return new TransactionInfo(
                signedTx,
                body,
                TransactionID.newBuilder().accountID(PAYER_ID).build(),
                PAYER_ID,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                SCHEDULE_SIGN,
                null);
    }

    private ThrottleDefinitions getThrottleDefs(String testResource) throws IOException, ParseException {
        try (InputStream in = ThrottleDefinitions.class.getClassLoader().getResourceAsStream(testResource)) {
            var om = new ObjectMapper();
            var throttleDefinitionsObj = om.readValue(
                    in, com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions.class);
            final var throttleDefsBytes =
                    Bytes.wrap(throttleDefinitionsObj.toProto().toByteArray());
            return ThrottleDefinitions.PROTOBUF.parse(throttleDefsBytes.toReadableSequentialData());
        }
    }

    private void givenMintWith(int numNfts) {
        final var op = TokenMintTransactionBody.newBuilder()
                .token(TokenID.newBuilder().tokenNum(123L).build()); // Add valid token
        if (numNfts == 0) {
            op.amount(1_234_567L);
        } else {
            final var metadata = new ArrayList<Bytes>();
            while (numNfts-- > 0) {
                metadata.add(Bytes.wrap("metadata" + numNfts));
            }

            op.metadata(metadata);
        }
        final var txn = TransactionBody.newBuilder().tokenMint(op).build();

        given(transactionInfo.functionality()).willReturn(TOKEN_MINT);
        given(transactionInfo.txBody()).willReturn(txn);
    }

    private void givenTransferWithImplicitCreations(int numImplicitCreations) {
        var accountAmounts = new ArrayList<AccountAmount>();
        for (int i = 1; i <= numImplicitCreations; i++) {
            accountAmounts.add(AccountAmount.newBuilder()
                    .accountID(AccountID.newBuilder()
                            .alias(Bytes.wrap("abcdeabcdeabcdeabcde"))
                            .build())
                    .amount(i)
                    .build());
        }

        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .transfers(
                        TransferList.newBuilder().accountAmounts(accountAmounts).build())
                .build();

        final var txn =
                TransactionBody.newBuilder().cryptoTransfer(cryptoTransferBody).build();
        given(transactionInfo.txBody()).willReturn(txn);
    }

    private void givenTransferWithAutoAssociations(int numAutoAssoc) {
        var accountAmounts = new ArrayList<AccountAmount>();
        for (int i = 1; i <= numAutoAssoc; i++) {
            accountAmounts.add(
                    AccountAmount.newBuilder().accountID(PAYER_ID).amount(-i).build());
            accountAmounts.add(
                    AccountAmount.newBuilder().accountID(RECEIVER_ID).amount(i).build());
        }

        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_ID)
                        .transfers(accountAmounts)
                        .build())
                .build();

        final var txn =
                TransactionBody.newBuilder().cryptoTransfer(cryptoTransferBody).build();
        given(transactionInfo.txBody()).willReturn(txn);
    }

    private void setupThrottleSubject() throws Exception {
        subject = new ThrottleAccumulator(
                () -> CAPACITY_SPLIT,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                gasThrottle,
                bytesThrottle,
                opsDurationThrottle);
        lenient().when(configProvider.getConfiguration()).thenReturn(configuration);
        lenient().when(configuration.getConfigData(AccountsConfig.class)).thenReturn(accountsConfig);
        lenient().when(accountsConfig.lastThrottleExempt()).thenReturn(100L);

        final var defs = getThrottleDefs("bootstrap/throttles.json");
        subject.rebuildFor(defs);
    }
}
