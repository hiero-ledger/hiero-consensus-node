// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_DECIMALS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_INITIAL_SUPPLY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_MAX_SUPPLY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.THREE_MONTHS_IN_SECONDS;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.A_COMPLEX_KEY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenAttributesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.service.token.records.TokenCreateStreamBuilder;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenCreateHandlerTest extends CryptoTokenHandlerTestBase {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = LENIENT)
    private EntityNumGenerator entityNumGenerator;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    private TokenCreateStreamBuilder recordBuilder;
    private TokenCreateHandler subject;
    private TransactionBody txn;
    private CustomFeesValidator customFeesValidator;
    private TokenAttributesValidator tokenFieldsValidator;
    private TokenCreateValidator tokenCreateValidator;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private PureChecksContext pureChecksContext;

    private static final TokenID newTokenId =
            TokenID.newBuilder().shardNum(SHARD).realmNum(REALM).tokenNum(3000L).build();
    private final AccountID autoRenewAccountId = ownerId;

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);
        tokenFieldsValidator = new TokenAttributesValidator();
        customFeesValidator = new CustomFeesValidator();
        tokenCreateValidator = new TokenCreateValidator(tokenFieldsValidator);
        subject = new TokenCreateHandler(idFactory, customFeesValidator, tokenCreateValidator);
        givenStoresAndConfig(handleContext);
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void handleWorksForFungibleCreate() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond(),
                        autoRenewSecs,
                        autoRenewAccountId));

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();

        subject.handle(handleContext);

        assertThat(writableTokenStore.get(newTokenId)).isNotNull();
        final var token = writableTokenStore.get(newTokenId);

        assertThat(token.treasuryAccountId()).isEqualTo(treasuryId);
        assertThat(token.tokenId()).isEqualTo(newTokenId);
        assertThat(token.totalSupply()).isEqualTo(1000L);
        assertThat(token.tokenType()).isEqualTo(TokenType.FUNGIBLE_COMMON);
        assertThat(token.expirationSecond())
                .isEqualTo(consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond());
        assertThat(token.freezeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.kycKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.adminKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.wipeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.supplyKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.feeScheduleKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.autoRenewSeconds()).isEqualTo(autoRenewSecs);
        assertThat(token.autoRenewAccountId()).isEqualTo(autoRenewAccountId);
        assertThat(token.decimals()).isZero();
        assertThat(token.name()).isEqualTo("TestToken");
        assertThat(token.symbol()).isEqualTo("TT");
        assertThat(token.memo()).isEqualTo("test token");
        assertThat(token.customFees()).isEqualTo(List.of(withFixedFee(hbarFixedFee), withFractionalFee(fractionalFee)));

        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNotNull();
        final var tokenRel = writableTokenRelStore.get(treasuryId, newTokenId);

        assertThat(tokenRel.balance()).isEqualTo(1000L);
        assertThat(tokenRel.tokenId()).isEqualTo(newTokenId);
        assertThat(tokenRel.accountId()).isEqualTo(treasuryId);
        assertThat(tokenRel.kycGranted()).isTrue();
        assertThat(tokenRel.automaticAssociation()).isFalse();
        assertThat(tokenRel.frozen()).isFalse();
        assertThat(tokenRel.nextToken()).isNull();
        assertThat(tokenRel.previousToken()).isNull();
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void handleWorksForFungibleCreateWithSelfDenominatedToken() {
        setUpTxnContext();
        final var customFees = List.of(withFixedFee(hbarFixedFee
                .copyBuilder()
                .denominatingTokenId(TokenID.newBuilder().tokenNum(0L).build())
                .build()));
        txn = new TokenCreateBuilder().withCustomFees(customFees).build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond(),
                        autoRenewSecs,
                        autoRenewAccountId));

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(feeCollectorId, newTokenId)).isNull();

        subject.handle(handleContext);

        assertThat(writableTokenStore.get(newTokenId)).isNotNull();
        final var token = writableTokenStore.get(newTokenId);
        final var expectedCustomFees = List.of(withFixedFee(
                hbarFixedFee.copyBuilder().denominatingTokenId(newTokenId).build()));

        assertThat(token.treasuryAccountId()).isEqualTo(treasuryId);
        assertThat(token.tokenId()).isEqualTo(newTokenId);
        assertThat(token.totalSupply()).isEqualTo(1000L);
        assertThat(token.tokenType()).isEqualTo(TokenType.FUNGIBLE_COMMON);
        assertThat(token.expirationSecond())
                .isEqualTo(consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond());
        assertThat(token.freezeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.kycKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.adminKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.wipeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.supplyKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.feeScheduleKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.autoRenewSeconds()).isEqualTo(autoRenewSecs);
        assertThat(token.autoRenewAccountId()).isEqualTo(autoRenewAccountId);
        assertThat(token.decimals()).isZero();
        assertThat(token.name()).isEqualTo("TestToken");
        assertThat(token.symbol()).isEqualTo("TT");
        assertThat(token.memo()).isEqualTo("test token");
        assertThat(token.customFees()).isEqualTo(expectedCustomFees);

        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNotNull();
        final var tokenRel = writableTokenRelStore.get(treasuryId, newTokenId);

        assertThat(tokenRel.balance()).isEqualTo(1000L);
        assertThat(tokenRel.tokenId()).isEqualTo(newTokenId);
        assertThat(tokenRel.accountId()).isEqualTo(treasuryId);
        assertThat(tokenRel.kycGranted()).isTrue();
        assertThat(tokenRel.automaticAssociation()).isFalse();
        assertThat(tokenRel.frozen()).isFalse();
        assertThat(tokenRel.nextToken()).isNull();
        assertThat(tokenRel.previousToken()).isNull();

        assertThat(writableTokenRelStore.get(feeCollectorId, newTokenId)).isNotNull();
        final var feeCollectorRel = writableTokenRelStore.get(feeCollectorId, newTokenId);

        assertThat(feeCollectorRel.balance()).isZero();
        assertThat(feeCollectorRel.tokenId()).isEqualTo(newTokenId);
        assertThat(feeCollectorRel.accountId()).isEqualTo(feeCollectorId);
        assertThat(feeCollectorRel.kycGranted()).isFalse();
        assertThat(feeCollectorRel.automaticAssociation()).isFalse();
        assertThat(feeCollectorRel.frozen()).isFalse();
        assertThat(feeCollectorRel.nextToken()).isNull();
        assertThat(feeCollectorRel.previousToken()).isNull();
    }

    @Test
    void failsIfAssociationLimitExceeded() {
        setUpTxnContext();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("entities.limitTokenAssociations", "true")
                .withValue("tokens.maxPerAccount", "0")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, null));

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED));
    }

    @Test
    void failsIfAssociationAlreadyExists() {
        setUpTxnContext();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("entities.limitTokenAssociations", "true")
                .withValue("tokens.maxPerAccount", "10")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, null));

        // Just to simulate existing token association , add to store. Only for testing
        writableTokenRelStore.put(TokenRelation.newBuilder()
                .tokenId(newTokenId)
                .accountId(treasuryId)
                .balance(1000L)
                .build());
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNotNull();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT));
    }

    @Test
    void failsIfAssociationLimitExceededWhileAssociatingCollector() {
        setUpTxnContext();
        final var customFees = List.of(
                withFixedFee(hbarFixedFee
                        .copyBuilder()
                        .denominatingTokenId(TokenID.newBuilder().tokenNum(0L).build())
                        .build()),
                withFractionalFee(fractionalFee));
        txn = new TokenCreateBuilder().withCustomFees(customFees).build();
        given(handleContext.body()).willReturn(txn);

        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("entities.limitTokenAssociations", "true")
                .withValue("tokens.maxPerAccount", "1")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(payerId, newTokenId)).isNull();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, null));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED));
    }

    @Test
    void doesntCreateAssociationIfItAlreadyExistsWhileAssociatingCollector() {
        setUpTxnContext();
        final var customFees = List.of(
                withFixedFee(hbarFixedFee
                        .copyBuilder()
                        .denominatingTokenId(TokenID.newBuilder().tokenNum(0L).build())
                        .build()),
                withFractionalFee(fractionalFee));
        txn = new TokenCreateBuilder().withCustomFees(customFees).build();
        given(handleContext.body()).willReturn(txn);

        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("entities.limitTokenAssociations", "true")
                .withValue("tokens.maxPerAccount", "10")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, null));

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();

        // Just to simulate existing token association , add to store. Only for testing
        final var prebuiltTokenRel = TokenRelation.newBuilder()
                .tokenId(newTokenId)
                .accountId(feeCollectorId)
                .balance(1000L)
                .build();
        writableTokenRelStore.put(prebuiltTokenRel);
        assertThat(writableTokenRelStore.get(feeCollectorId, newTokenId)).isNotNull();

        subject.handle(handleContext);

        final var relAfterHandle = writableTokenRelStore.get(feeCollectorId, newTokenId);

        assertThat(relAfterHandle).isNotNull();
        assertThat(relAfterHandle.tokenId()).isEqualTo(prebuiltTokenRel.tokenId());
        assertThat(relAfterHandle.accountId()).isEqualTo(prebuiltTokenRel.accountId());
        assertThat(relAfterHandle.balance()).isEqualTo(prebuiltTokenRel.balance());
    }

    @Test
    void uniqueNotSupportedIfNftsNotEnabled() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("tokens.nfts.areEnabled", "false")
                .getOrCreateConfig();
        txn = new TokenCreateBuilder().withUniqueToken().build();
        given(handleContext.configuration()).willReturn(configOverride);
        given(handleContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(NOT_SUPPORTED));
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void uniqueSupportedIfNftsEnabled() {
        setUpTxnContext();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("tokens.nfts.areEnabled", "true")
                .getOrCreateConfig();
        txn = new TokenCreateBuilder()
                .withUniqueToken()
                .withCustomFees(List.of(withRoyaltyFee(royaltyFee)))
                .build();
        given(handleContext.configuration()).willReturn(configOverride);
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond(), 100L, autoRenewAccountId));

        assertThat(writableTokenStore.get(newTokenId)).isNull();
        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNull();

        subject.handle(handleContext);

        assertThat(writableTokenStore.get(newTokenId)).isNotNull();
        final var token = writableTokenStore.get(newTokenId);

        assertThat(token.treasuryAccountId()).isEqualTo(treasuryId);
        assertThat(token.tokenId()).isEqualTo(newTokenId);
        assertThat(token.totalSupply()).isZero();
        assertThat(token.tokenType()).isEqualTo(TokenType.NON_FUNGIBLE_UNIQUE);
        assertThat(token.expirationSecond())
                .isEqualTo(consensusInstant.plusSeconds(autoRenewSecs).getEpochSecond());
        assertThat(token.freezeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.kycKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.adminKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.wipeKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.supplyKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.feeScheduleKey()).isEqualTo(A_COMPLEX_KEY);
        assertThat(token.autoRenewSeconds()).isEqualTo(autoRenewSecs);
        assertThat(token.autoRenewAccountId()).isEqualTo(autoRenewAccountId);
        assertThat(token.decimals()).isZero();
        assertThat(token.name()).isEqualTo("TestToken");
        assertThat(token.symbol()).isEqualTo("TT");
        assertThat(token.memo()).isEqualTo("test token");
        assertThat(token.customFees()).isEqualTo(List.of(withRoyaltyFee(royaltyFee)));

        assertThat(writableTokenRelStore.get(treasuryId, newTokenId)).isNotNull();
        final var tokenRel = writableTokenRelStore.get(treasuryId, newTokenId);

        assertThat(tokenRel.balance()).isZero();
        assertThat(tokenRel.tokenId()).isEqualTo(newTokenId);
        assertThat(tokenRel.accountId()).isEqualTo(treasuryId);
        assertThat(tokenRel.kycGranted()).isTrue();
        assertThat(tokenRel.automaticAssociation()).isFalse();
        assertThat(tokenRel.frozen()).isFalse();
        assertThat(tokenRel.nextToken()).isNull();
        assertThat(tokenRel.previousToken()).isNull();
    }

    @Test
    void validatesInPureChecks() {
        setUpTxnContext();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void acceptsMissingAutoRenewAcountInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withAutoRenewAccount(AccountID.newBuilder().accountNum(200000L).build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void failsOnMissingAutoRenewAcountInHandle() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var invalidAutoRenewId =
                AccountID.newBuilder().accountNum(200000L).build();
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, invalidAutoRenewId));
        txn = new TokenCreateBuilder().withAutoRenewAccount(invalidAutoRenewId).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_AUTORENEW_ACCOUNT));
    }

    @Test
    void failsForZeroLengthSymbol() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withSymbol("").build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MISSING_TOKEN_SYMBOL));
    }

    @Test
    void failsForNullSymbol() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withSymbol(null).build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MISSING_TOKEN_SYMBOL));
    }

    @Test
    void failsForVeryLongSymbol() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder()
                .withSymbol("1234567890123456789012345678901234567890123456789012345678901234567890")
                .build();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("tokens.maxSymbolUtf8Bytes", "10")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_SYMBOL_TOO_LONG));
    }

    @Test
    void failsForZeroLengthName() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withName("").build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MISSING_TOKEN_NAME));
    }

    @Test
    void failsForNullName() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withName(null).build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MISSING_TOKEN_NAME));
    }

    @Test
    void failsForVeryLongName() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder()
                .withName("1234567890123456789012345678901234567890123456789012345678901234567890")
                .build();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("tokens.maxTokenNameUtf8Bytes", "10")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configOverride, 1));
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NAME_TOO_LONG));
    }

    @Test
    void failsForNegativeInitialSupplyForFungibleTokenInPreCheck() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withInitialSupply(-1).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_INITIAL_SUPPLY));
    }

    @Test
    void failsForNonZeroInitialSupplyForNFTInPreCheck() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .withInitialSupply(1)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_INITIAL_SUPPLY));
    }

    @Test
    void failsForNegativeDecimalsForFungibleTokenInPreCheck() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withDecimals(-1).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_DECIMALS));
    }

    @Test
    void failsForNonZeroDecimalsForNFTInPreCheck() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .withDecimals(1)
                .withInitialSupply(0)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_DECIMALS));
    }

    @Test
    void failsOnMissingTreasury() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withTreasury(AccountID.newBuilder().accountNum(200000L).build())
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TREASURY_ACCOUNT_FOR_TOKEN));
    }

    @Test
    void failsForInvalidFeeScheduleKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withFeeScheduleKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_SCHEDULE_KEY));
    }

    @Test
    void failsForInvalidAdminKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withAdminKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ADMIN_KEY));
    }

    @Test
    void acceptsSentinelAdminKeyForImmutableObjects() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(1L, THREE_MONTHS_IN_SECONDS, null));
        txn = new TokenCreateBuilder().withAdminKey(IMMUTABILITY_SENTINEL_KEY).build();
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void failsForInvalidSupplyKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withSupplyKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_SUPPLY_KEY));
    }

    @Test
    void failsForInvalidKycKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withKycKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_KYC_KEY));
    }

    @Test
    void failsForInvalidWipeKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withWipeKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_WIPE_KEY));
    }

    @Test
    void failsForInvalidFreezeKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withFreezeKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_FREEZE_KEY));
    }

    @Test
    void failsIfFreezeDefaultAndNoFreezeKey() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withFreezeDefault().withFreezeKey(null).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_HAS_NO_FREEZE_KEY));
    }

    @Test
    void succeedsIfFreezeDefaultWithFreezeKey() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withFreezeDefault().build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void failsOnInvalidMemo() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        doThrow(new HandleException(INVALID_ZERO_BYTE_IN_STRING))
                .when(attributeValidator)
                .validateMemo(any());
        txn = new TokenCreateBuilder().withMemo("\0").build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ZERO_BYTE_IN_STRING));
    }

    @Test
    void failsOnInvalidAutoRenewPeriod() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willThrow(new HandleException(INVALID_RENEWAL_PERIOD));

        txn = new TokenCreateBuilder().withAutoRenewPeriod(30001L).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_RENEWAL_PERIOD));

        txn = new TokenCreateBuilder().withAutoRenewPeriod(100).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_RENEWAL_PERIOD));
    }

    @Test
    void failsOnExpiryPastConsensusTime() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willThrow(new HandleException(INVALID_EXPIRATION_TIME));
        txn = new TokenCreateBuilder()
                .withAutoRenewPeriod(0)
                .withExpiry(consensusInstant.getEpochSecond() - 1)
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void rejectsInvalidMaxSupplyForInfiniteSupplyInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withSupplyType(TokenSupplyType.INFINITE)
                .withMaxSupply(1)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_MAX_SUPPLY));
    }

    @Test
    void rejectsInvalidMaxSupplyforFiniteSupplyInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withSupplyType(TokenSupplyType.FINITE)
                .withMaxSupply(0)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_MAX_SUPPLY));
    }

    @Test
    void failsOnInvalidInitialAndMaxSupplyInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withInitialSupply(100).withMaxSupply(10).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_INITIAL_SUPPLY));
    }

    @Test
    void failsOnMissingSupplyKeyOnNftCreateInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withUniqueToken().withSupplyKey(null).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_HAS_NO_SUPPLY_KEY));
    }

    @Test
    void succeedsWithSupplyKeyOnNftCreateInPureChecks() {
        setUpTxnContext();
        txn = new TokenCreateBuilder().withUniqueToken().build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void succeedsWithSupplyMetaDataAndKey() {
        setUpTxnContext();
        txn = new TokenCreateBuilder()
                .withMetadataKey(metadataKey)
                .withMetadata(String.valueOf(metadata))
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThat(txn.data().value()).toString().contains("test metadata");
        assertThat(txn.data().value()).hasNoNullFieldsOrProperties();
    }

    @Test
    void failsForInvalidMetaDataKey() {
        setUpTxnContext();
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenCreateBuilder().withMetadataKey(Key.DEFAULT).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_METADATA_KEY));
    }

    /* --------------------------------- Helpers */
    /**
     * A builder for {@link com.hedera.hapi.node.transaction.TransactionBody} instances.
     */
    private class TokenCreateBuilder {
        private AccountID payer = payerId;
        private AccountID treasury = treasuryId;
        private Key adminKey = key;
        private boolean isUnique = false;
        private String name = "TestToken";
        private String symbol = "TT";
        private Key kycKey = A_COMPLEX_KEY;
        private Key freezeKey = A_COMPLEX_KEY;
        private Key wipeKey = A_COMPLEX_KEY;
        private Key supplyKey = A_COMPLEX_KEY;
        private Key feeScheduleKey = A_COMPLEX_KEY;
        private Key pauseKey = A_COMPLEX_KEY;
        private Key metadataKey = A_COMPLEX_KEY;
        private String metadata = "test metadata";
        private Timestamp expiry = Timestamp.newBuilder().seconds(1234600L).build();
        private AccountID autoRenewAccount = autoRenewAccountId;
        private long autoRenewPeriod = autoRenewSecs;
        private String memo = "test token";
        private TokenType tokenType = TokenType.FUNGIBLE_COMMON;
        private TokenSupplyType supplyType = TokenSupplyType.FINITE;
        private long maxSupply = 10000L;
        private int decimals = 0;
        private long initialSupply = 1000L;
        private boolean freezeDefault = false;
        private List<CustomFee> customFees = List.of(withFixedFee(hbarFixedFee), withFractionalFee(fractionalFee));

        private TokenCreateBuilder() {}

        public TransactionBody build() {
            final var transactionID =
                    TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
            final var createTxnBody = TokenCreateTransactionBody.newBuilder()
                    .tokenType(tokenType)
                    .symbol(symbol)
                    .name(name)
                    .treasury(treasury)
                    .adminKey(adminKey)
                    .supplyKey(supplyKey)
                    .kycKey(kycKey)
                    .freezeKey(freezeKey)
                    .wipeKey(wipeKey)
                    .feeScheduleKey(feeScheduleKey)
                    .pauseKey(pauseKey)
                    .autoRenewAccount(autoRenewAccount)
                    .expiry(expiry)
                    .freezeDefault(freezeDefault)
                    .memo(memo)
                    .maxSupply(maxSupply)
                    .supplyType(supplyType)
                    .customFees(customFees)
                    .metadataKey(metadataKey)
                    .metadata(Bytes.wrap(metadata));
            if (autoRenewPeriod > 0) {
                createTxnBody.autoRenewPeriod(
                        Duration.newBuilder().seconds(autoRenewPeriod).build());
            }
            if (isUnique) {
                createTxnBody.tokenType(TokenType.NON_FUNGIBLE_UNIQUE);
                createTxnBody.initialSupply(0L);
                createTxnBody.decimals(0);
            } else {
                createTxnBody.decimals(decimals);
                createTxnBody.initialSupply(initialSupply);
            }
            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .tokenCreation(createTxnBody.build())
                    .build();
        }

        public TokenCreateBuilder withUniqueToken() {
            this.isUnique = true;
            return this;
        }

        public TokenCreateBuilder withCustomFees(List<CustomFee> fees) {
            this.customFees = fees;
            return this;
        }

        public TokenCreateBuilder withFreezeKey(Key freezeKey) {
            this.freezeKey = freezeKey;
            return this;
        }

        public TokenCreateBuilder withAutoRenewAccount(AccountID autoRenewAccount) {
            this.autoRenewAccount = autoRenewAccount;
            return this;
        }

        public TokenCreateBuilder withSymbol(final String symbol) {
            this.symbol = symbol;
            return this;
        }

        public TokenCreateBuilder withName(final String name) {
            this.name = name;
            return this;
        }

        public TokenCreateBuilder withInitialSupply(final long number) {
            this.initialSupply = number;
            return this;
        }

        public TokenCreateBuilder withTokenType(final TokenType type) {
            this.tokenType = type;
            return this;
        }

        public TokenCreateBuilder withDecimals(final int decimals) {
            this.decimals = decimals;
            return this;
        }

        public TokenCreateBuilder withTreasury(final AccountID treasury) {
            this.treasury = treasury;
            return this;
        }

        public TokenCreateBuilder withFeeScheduleKey(final Key key) {
            this.feeScheduleKey = key;
            return this;
        }

        public TokenCreateBuilder withAdminKey(final Key key) {
            this.adminKey = key;
            return this;
        }

        public TokenCreateBuilder withSupplyKey(final Key key) {
            this.supplyKey = key;
            return this;
        }

        public TokenCreateBuilder withKycKey(final Key key) {
            this.kycKey = key;
            return this;
        }

        public TokenCreateBuilder withWipeKey(final Key key) {
            this.wipeKey = key;
            return this;
        }

        public TokenCreateBuilder withMaxSupply(final long maxSupply) {
            this.maxSupply = maxSupply;
            return this;
        }

        public TokenCreateBuilder withSupplyType(final TokenSupplyType supplyType) {
            this.supplyType = supplyType;
            return this;
        }

        public TokenCreateBuilder withExpiry(final long expiry) {
            this.expiry = Timestamp.newBuilder().seconds(expiry).build();
            return this;
        }

        public TokenCreateBuilder withAutoRenewPeriod(final long autoRenewPeriod) {
            this.autoRenewPeriod = autoRenewPeriod;
            return this;
        }

        public TokenCreateBuilder withMemo(final String s) {
            this.memo = s;
            return this;
        }

        public TokenCreateBuilder withFreezeDefault() {
            this.freezeDefault = true;
            return this;
        }

        public TokenCreateBuilder withMetadata(final String s) {
            this.metadata = s;
            return this;
        }

        public TokenCreateBuilder withMetadataKey(final Key k) {
            this.metadataKey = k;
            return this;
        }
    }

    private void setUpTxnContext() {
        txn = new TokenCreateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.entityNumGenerator()).willReturn(entityNumGenerator);
        given(entityNumGenerator.newEntityNum()).willReturn(newTokenId.tokenNum());
    }
}
