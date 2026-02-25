// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAUSE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.A_KEY_LIST;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.B_COMPLEX_KEY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenKeyValidation;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.TokenAttributesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenUpdateValidator;
import com.hedera.node.app.service.token.records.TokenUpdateStreamBuilder;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenUpdateHandlerTest extends CryptoTokenHandlerTestBase {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock(strictness = LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = LENIENT)
    private TokenUpdateStreamBuilder recordBuilder;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    private TransactionBody txn;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private PureChecksContext pureChecksContext;

    private TokenUpdateHandler subject;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        final TokenUpdateValidator validator = new TokenUpdateValidator(new TokenAttributesValidator());
        subject = new TokenUpdateHandler(validator);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
        givenStoresAndConfig(handleContext);
        setUpTxnContext();
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void happyPathForFungibleTokenUpdate() {
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        final var token = readableTokenStore.get(fungibleTokenId);
        assertThat(token.symbol()).isEqualTo(fungibleToken.symbol());
        assertThat(token.name()).isEqualTo(fungibleToken.name());
        assertThat(token.treasuryAccountId()).isEqualTo(fungibleToken.treasuryAccountId());
        assertThat(token.adminKey()).isEqualTo(fungibleToken.adminKey());
        assertThat(token.supplyKey()).isEqualTo(fungibleToken.supplyKey());
        assertThat(token.kycKey()).isEqualTo(fungibleToken.kycKey());
        assertThat(token.freezeKey()).isEqualTo(fungibleToken.freezeKey());
        assertThat(token.metadataKey()).isEqualTo(fungibleToken.metadataKey());
        assertThat(token.wipeKey()).isEqualTo(fungibleToken.wipeKey());
        assertThat(token.feeScheduleKey()).isEqualTo(fungibleToken.feeScheduleKey());
        assertThat(token.pauseKey()).isEqualTo(fungibleToken.pauseKey());
        assertThat(token.autoRenewAccountId()).isEqualTo(fungibleToken.autoRenewAccountId());
        assertThat(token.expirationSecond()).isEqualTo(fungibleToken.expirationSecond());
        assertThat(token.memo()).isEqualTo(fungibleToken.memo());
        assertThat(token.metadata()).isEqualTo(fungibleToken.metadata());
        assertThat(token.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(FUNGIBLE_COMMON);
        assertThat(token.metadataKey()).isEqualTo(fungibleToken.metadataKey());
        assertThat(token.metadata()).isEqualTo(fungibleToken.metadata());

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.kycKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.freezeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(FUNGIBLE_COMMON);
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void happyPathForNonFungibleTokenUpdate() {
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);

        final var token = readableTokenStore.get(nonFungibleTokenId);
        assertThat(token.symbol()).isEqualTo(nonFungibleToken.symbol());
        assertThat(token.name()).isEqualTo(nonFungibleToken.name());
        assertThat(token.treasuryAccountId()).isEqualTo(nonFungibleToken.treasuryAccountId());
        assertThat(token.adminKey()).isEqualTo(nonFungibleToken.adminKey());
        assertThat(token.supplyKey()).isEqualTo(nonFungibleToken.supplyKey());
        assertThat(token.kycKey()).isEqualTo(nonFungibleToken.kycKey());
        assertThat(token.freezeKey()).isEqualTo(nonFungibleToken.freezeKey());
        assertThat(token.metadataKey()).isEqualTo(fungibleToken.metadataKey());
        assertThat(token.wipeKey()).isEqualTo(nonFungibleToken.wipeKey());
        assertThat(token.feeScheduleKey()).isEqualTo(nonFungibleToken.feeScheduleKey());
        assertThat(token.pauseKey()).isEqualTo(nonFungibleToken.pauseKey());
        assertThat(token.autoRenewAccountId()).isEqualTo(nonFungibleToken.autoRenewAccountId());
        assertThat(token.expirationSecond()).isEqualTo(nonFungibleToken.expirationSecond());
        assertThat(token.memo()).isEqualTo(nonFungibleToken.memo());
        assertThat(token.metadata()).isEqualTo(nonFungibleToken.metadata());
        assertThat(token.autoRenewSeconds()).isEqualTo(nonFungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(NON_FUNGIBLE_UNIQUE);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.kycKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.freezeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(NON_FUNGIBLE_UNIQUE);
    }

    @Test
    void succeedsWithSupplyMetaDataAndKey() {
        setUpTxnContext();
        txn = new TokenUpdateBuilder()
                .withMetadataKey(metadataKey)
                .withMetadata(String.valueOf(metadata))
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThat(txn.data().value()).toString().contains("test metadata");
    }

    @Test
    void failsForInvalidMetaDataKey() {
        txn = new TokenUpdateBuilder().withMetadataKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_METADATA_KEY));
    }

    @Test
    void invalidTokenFails() {
        txn = new TokenUpdateBuilder().withToken(asToken(1000)).build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    void failsIfTokenImmutable() {
        final var copyToken = writableTokenStore
                .get(fungibleTokenId)
                .copyBuilder()
                .adminKey((Key) null)
                .build();
        writableTokenStore.put(copyToken);
        given(preHandleContext.createStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        txn = new TokenUpdateBuilder().build();
        given(preHandleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_IS_IMMUTABLE));
    }

    @Test
    void invalidKeysForTokenFails() {
        final Key invalidAllZeros = Key.newBuilder()
                .ecdsaSecp256k1((Bytes.fromHex("0000000000000000000000000000000000000000")))
                .build();
        txn = new TokenUpdateBuilder().withAdminKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ADMIN_KEY));

        txn = new TokenUpdateBuilder().withFreezeKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_FREEZE_KEY));

        txn = new TokenUpdateBuilder().withKycKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_KYC_KEY));

        txn = new TokenUpdateBuilder().withWipeKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_WIPE_KEY));

        txn = new TokenUpdateBuilder().withSupplyKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_SUPPLY_KEY));

        txn = new TokenUpdateBuilder().withPauseKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_PAUSE_KEY));

        txn = new TokenUpdateBuilder().withFeeScheduleKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_SCHEDULE_KEY));

        txn = new TokenUpdateBuilder().withMetadataKey(invalidAllZeros).build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_METADATA_KEY));
    }

    @Test
    void invalidKeysForTokenSucceedsIfNoValidationIsApplied() {
        final var copyToken = writableTokenStore
                .get(fungibleTokenId)
                .copyBuilder()
                .adminKey(Key.DEFAULT)
                .wipeKey(Key.DEFAULT)
                .kycKey(Key.DEFAULT)
                .supplyKey(Key.DEFAULT)
                .freezeKey(Key.DEFAULT)
                .feeScheduleKey(Key.DEFAULT)
                .pauseKey(Key.DEFAULT)
                .metadataKey(Key.DEFAULT)
                .build();
        writableTokenStore.put(copyToken);

        given(storeFactory.writableStore((WritableTokenStore.class))).willReturn(writableTokenStore);

        final Key invalidAllZeros = Key.newBuilder()
                .ecdsaSecp256k1((Bytes.fromHex("0000000000000000000000000000000000000000")))
                .build();
        txn = new TokenUpdateBuilder()
                .withKeyVerification(TokenKeyValidation.NO_VALIDATION)
                .withAdminKey(invalidAllZeros)
                .withFreezeKey(invalidAllZeros)
                .withKycKey(invalidAllZeros)
                .withWipeKey(invalidAllZeros)
                .withSupplyKey(invalidAllZeros)
                .withPauseKey(invalidAllZeros)
                .withFeeScheduleKey(invalidAllZeros)
                .withMetadataKey(invalidAllZeros)
                .build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.adminKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.freezeKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.kycKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.wipeKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.supplyKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.pauseKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(invalidAllZeros);
        assertThat(modifiedToken.metadataKey()).isEqualTo(invalidAllZeros);
    }

    // previous to HIP-540 this should fail because the assumption was
    // if an admin key is not set, the token is immutable
    // after the HIP we expect other keys to be able to update themselves as well
    @Test
    void validTokenKeysSucceedsEvenIfAdminKeyIsNotSet() {
        final var copyToken = writableTokenStore
                .get(fungibleTokenId)
                .copyBuilder()
                .adminKey((Key) null)
                .wipeKey(Key.DEFAULT)
                .kycKey(Key.DEFAULT)
                .supplyKey(Key.DEFAULT)
                .freezeKey(Key.DEFAULT)
                .feeScheduleKey(Key.DEFAULT)
                .pauseKey(Key.DEFAULT)
                .metadataKey(Key.DEFAULT)
                .build();
        writableTokenStore.put(copyToken);
        given(storeFactory.writableStore((WritableTokenStore.class))).willReturn(writableTokenStore);

        txn = new TokenUpdateBuilder()
                .withFreezeKey(A_KEY_LIST)
                .withKycKey(A_KEY_LIST)
                .withWipeKey(A_KEY_LIST)
                .withSupplyKey(A_KEY_LIST)
                .withPauseKey(A_KEY_LIST)
                .withFeeScheduleKey(A_KEY_LIST)
                .withMetadataKey(A_KEY_LIST)
                .build();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.freezeKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.kycKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.wipeKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.supplyKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.pauseKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(A_KEY_LIST);
        assertThat(modifiedToken.metadataKey()).isEqualTo(A_KEY_LIST);
    }

    @Test
    void failsIfTokenHasNoKycGrantedImmutable() {
        final var copyTokenRel = writableTokenRelStore
                .get(treasuryId, fungibleTokenId)
                .copyBuilder()
                .kycGranted(false)
                .build();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        writableTokenRelStore.put(copyTokenRel);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_HAS_NO_KYC_KEY));
    }

    @Test
    void failsIfTokenRelIsFrozen() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var copyTokenRel = writableTokenRelStore
                .get(treasuryId, fungibleTokenId)
                .copyBuilder()
                .frozen(true)
                .build();
        writableTokenRelStore.put(copyTokenRel);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_FROZEN_FOR_TOKEN));
    }

    @Test
    void failsIfMemoTooLong() {
        doThrow(new HandleException(MEMO_TOO_LONG)).when(attributeValidator).validateMemo(any());
        txn = new TokenUpdateBuilder()
                .withMemo("12345678904634634563436462343254534e5365453435452454524541534353665324545435")
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MEMO_TOO_LONG));
    }

    @Test
    void failsIfMemoHasZeroByte() {
        doThrow(new HandleException(INVALID_ZERO_BYTE_IN_STRING))
                .when(attributeValidator)
                .validateMemo(any());
        txn = new TokenUpdateBuilder().withMemo("\0").build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ZERO_BYTE_IN_STRING));
    }

    @Test
    void doesntFailForZeroLengthSymbolUpdate() {
        txn = new TokenUpdateBuilder().withSymbol("").build();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void doesntFailForNullSymbol() {
        setUpTxnContext();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder().withSymbol(null).build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void failsForVeryLongSymbol() {
        setUpTxnContext();
        txn = new TokenUpdateBuilder()
                .withSymbol("1234567890123456789012345678901234567890123456789012345678901234567890")
                .build();
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("tokens.maxSymbolUtf8Bytes", "10")
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configOverride, 1));
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        Assertions.assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_SYMBOL_TOO_LONG));
    }

    @Test
    void doesntFailForZeroLengthName() {
        txn = new TokenUpdateBuilder().withName("").build();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void doesntFailForNullName() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder().withName(null).build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void failsForVeryLongName() {
        txn = new TokenUpdateBuilder()
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
        Assertions.assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NAME_TOO_LONG));
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void worksWithUnassociatedNewTreasuryIfAutoAssociationsAvailable() {
        txn = new TokenUpdateBuilder()
                .withTreasury(payerId)
                .withToken(fungibleTokenId)
                .build();
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        assertThat(writableTokenRelStore.get(payerId, fungibleTokenId)).isNull();

        final var token = readableTokenStore.get(fungibleTokenId);
        assertThat(token.symbol()).isEqualTo(fungibleToken.symbol());
        assertThat(token.name()).isEqualTo(fungibleToken.name());
        assertThat(token.treasuryAccountId()).isEqualTo(fungibleToken.treasuryAccountId());
        assertThat(token.adminKey()).isEqualTo(fungibleToken.adminKey());
        assertThat(token.supplyKey()).isEqualTo(fungibleToken.supplyKey());
        assertThat(token.kycKey()).isEqualTo(fungibleToken.kycKey());
        assertThat(token.freezeKey()).isEqualTo(fungibleToken.freezeKey());
        assertThat(token.wipeKey()).isEqualTo(fungibleToken.wipeKey());
        assertThat(token.feeScheduleKey()).isEqualTo(fungibleToken.feeScheduleKey());
        assertThat(token.pauseKey()).isEqualTo(fungibleToken.pauseKey());
        assertThat(token.autoRenewAccountId()).isEqualTo(fungibleToken.autoRenewAccountId());
        assertThat(token.expirationSecond()).isEqualTo(fungibleToken.expirationSecond());
        assertThat(token.memo()).isEqualTo(fungibleToken.memo());
        assertThat(token.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(FUNGIBLE_COMMON);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var rel = writableTokenRelStore.get(payerId, fungibleTokenId);

        assertThat(rel).isNotNull();
        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(payerId);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.kycKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.freezeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(modifiedToken.tokenType()).isEqualTo(FUNGIBLE_COMMON);

        assertThat(rel.frozen()).isFalse();
        assertThat(rel.kycGranted()).isTrue();

        verify(recordBuilder)
                .addAutomaticTokenAssociation(TokenAssociation.newBuilder()
                        .tokenId(fungibleTokenId)
                        .accountId(payerId)
                        .build());
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void worksWithUnassociatedNewTreasuryIfAutoAssociationsAvailableForNFT() {
        txn = new TokenUpdateBuilder()
                .withTreasury(payerId)
                .withToken(nonFungibleTokenId)
                .build();
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(nonFungibleTokenId)
                .accountId(payerId)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        assertThat(writableTokenRelStore.get(payerId, nonFungibleTokenId)).isNull();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        final var token = readableTokenStore.get(nonFungibleTokenId);
        assertThat(token.symbol()).isEqualTo(nonFungibleToken.symbol());
        assertThat(token.name()).isEqualTo(nonFungibleToken.name());
        assertThat(token.treasuryAccountId()).isEqualTo(nonFungibleToken.treasuryAccountId());
        assertThat(token.adminKey()).isEqualTo(nonFungibleToken.adminKey());
        assertThat(token.supplyKey()).isEqualTo(nonFungibleToken.supplyKey());
        assertThat(token.kycKey()).isEqualTo(nonFungibleToken.kycKey());
        assertThat(token.freezeKey()).isEqualTo(nonFungibleToken.freezeKey());
        assertThat(token.wipeKey()).isEqualTo(nonFungibleToken.wipeKey());
        assertThat(token.feeScheduleKey()).isEqualTo(nonFungibleToken.feeScheduleKey());
        assertThat(token.pauseKey()).isEqualTo(nonFungibleToken.pauseKey());
        assertThat(token.autoRenewAccountId()).isEqualTo(nonFungibleToken.autoRenewAccountId());
        assertThat(token.expirationSecond()).isEqualTo(nonFungibleToken.expirationSecond());
        assertThat(token.memo()).isEqualTo(nonFungibleToken.memo());
        assertThat(token.autoRenewSeconds()).isEqualTo(nonFungibleToken.autoRenewSeconds());
        assertThat(token.tokenType()).isEqualTo(NON_FUNGIBLE_UNIQUE);

        final var newTreasury = writableAccountStore.get(payerId);
        final var oldTreasury = writableAccountStore.get(treasuryId);
        assertThat(newTreasury.numberOwnedNfts()).isEqualTo(2);
        assertThat(oldTreasury.numberOwnedNfts()).isEqualTo(2);

        final var newTreasuryRel = writableTokenRelStore.get(payerId, nonFungibleTokenId);
        final var oldTreasuryRel = writableTokenRelStore.get(treasuryId, nonFungibleTokenId);

        assertThat(newTreasuryRel).isNull();
        assertThat(oldTreasuryRel.balance()).isEqualTo(1);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var rel = writableTokenRelStore.get(payerId, nonFungibleTokenId);

        assertThat(rel).isNotNull();
        final var modifiedToken = writableTokenStore.get(nonFungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(payerId);
        assertThat(rel.balance()).isEqualTo(1);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.kycKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.freezeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(modifiedToken.tokenType()).isEqualTo(NON_FUNGIBLE_UNIQUE);

        assertThat(rel.frozen()).isFalse();
        assertThat(rel.kycGranted()).isTrue();

        final var modifiedNewTreasury = writableAccountStore.get(payerId);
        final var modifiedOldTreasury = writableAccountStore.get(treasuryId);
        assertThat(modifiedNewTreasury.numberOwnedNfts()).isEqualTo(3);
        assertThat(modifiedOldTreasury.numberOwnedNfts()).isEqualTo(1);

        verify(recordBuilder)
                .addAutomaticTokenAssociation(TokenAssociation.newBuilder()
                        .tokenId(nonFungibleTokenId)
                        .accountId(payerId)
                        .build());
    }

    @Test
    void failsIfNoAutoAssociationsAvailableForNewUnassociatedTreasury() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder()
                .withTreasury(payerId)
                .withToken(fungibleTokenId)
                .build();
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        writableAccountStore.put(account.copyBuilder()
                .maxAutoAssociations(0)
                .usedAutoAssociations(0)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        assertThat(writableTokenRelStore.get(payerId, fungibleTokenId)).isNull();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(NO_REMAINING_AUTOMATIC_ASSOCIATIONS));
    }

    @Test
    void failsOnInvalidNewTreasury() {
        txn = new TokenUpdateBuilder().withTreasury(asAccount(0L, 0L, 2000000)).build();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TREASURY_ACCOUNT_FOR_TOKEN));
    }

    @Test
    void failsOnDetachedNewTreasury() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder().withTreasury(payerId).build();
        writableAccountStore.put(account.copyBuilder()
                .expiredAndPendingRemoval(true)
                .tinybarBalance(0)
                .expirationSecond(consensusInstant.getEpochSecond() - 10000)
                .build());
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoRenew.targetTypes", "CONTRACT,ACCOUNT")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .willReturn(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsOnDetachedOldTreasury() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder().build();
        writableAccountStore.put(treasuryAccount
                .copyBuilder()
                .expiredAndPendingRemoval(true)
                .tinybarBalance(0)
                .expirationSecond(consensusInstant.getEpochSecond() - 10000)
                .build());
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoRenew.targetTypes", "CONTRACT,ACCOUNT")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .willReturn(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsOnDetachedNewAutoRenewAccount() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder().withAutoRenewAccount(payerId).build();
        writableAccountStore.put(account.copyBuilder()
                .expiredAndPendingRemoval(true)
                .tinybarBalance(0)
                .expirationSecond(consensusInstant.getEpochSecond() - 10000)
                .build());
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoRenew.targetTypes", "CONTRACT,ACCOUNT")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .willReturn(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsOnDetachedOldAutoRenewAccount() {
        txn = new TokenUpdateBuilder().build();
        writableAccountStore.put(spenderAccount
                .copyBuilder()
                .expiredAndPendingRemoval(true)
                .tinybarBalance(0)
                .expirationSecond(consensusInstant.getEpochSecond() - 10000)
                .build());
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoRenew.targetTypes", "CONTRACT,ACCOUNT")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .willReturn(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsOnDeletedOldAutoRenewAccount() {
        txn = new TokenUpdateBuilder().build();
        writableAccountStore.put(spenderAccount.copyBuilder().deleted(true).build());
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        given(expiryValidator.resolveUpdateAttempt(any(), any())).willThrow(new HandleException(ACCOUNT_DELETED));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_DELETED));
    }

    @Test
    void failsOnDeletedNewAutoRenewAccount() {
        txn = new TokenUpdateBuilder().withAutoRenewAccount(payerId).build();
        writableAccountStore.put(account.copyBuilder().deleted(true).build());
        given(handleContext.body()).willReturn(txn);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        given(expiryValidator.resolveUpdateAttempt(any(), any())).willThrow(new HandleException(ACCOUNT_DELETED));

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_DELETED));
    }

    @Test
    void permitsExtendingOnlyExpiryWithoutAdminKey() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        final var transactionID =
                TransactionID.newBuilder().accountID(payerId).transactionValidStart(consensusTimestamp);
        final var body = TokenUpdateTransactionBody.newBuilder()
                .token(fungibleTokenId)
                .expiry(Timestamp.newBuilder().seconds(1234600L).build())
                .build();
        txn = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .tokenUpdate(body)
                .build();

        given(handleContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));
    }

    @Test
    void failsOnReducedNewExpiry() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willThrow(new HandleException(EXPIRATION_REDUCTION_NOT_ALLOWED));
        txn = new TokenUpdateBuilder()
                .withExpiry(consensusInstant.getEpochSecond() - 72000)
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(EXPIRATION_REDUCTION_NOT_ALLOWED));
    }

    @Test
    void failsOnInvalidNewExpiry() {
        txn = new TokenUpdateBuilder().withExpiry(3_000_000_000L + 10).build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willThrow(new HandleException(INVALID_EXPIRATION_TIME));
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void failsOnAlreadyDeletedToken() {
        final var copyToken = writableTokenStore
                .get(fungibleTokenId)
                .copyBuilder()
                .deleted(true)
                .build();
        writableTokenStore.put(copyToken);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_WAS_DELETED));
    }

    @Test
    void failsOnPausedToken() {
        final var copyToken = writableTokenStore
                .get(fungibleTokenId)
                .copyBuilder()
                .paused(true)
                .build();
        writableTokenStore.put(copyToken);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        txn = new TokenUpdateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_IS_PAUSED));
    }

    @Test
    void doesntReplaceIdenticalTreasury() {
        final var transactionID =
                TransactionID.newBuilder().accountID(payerId).transactionValidStart(consensusTimestamp);
        final var body = TokenUpdateTransactionBody.newBuilder()
                .token(fungibleTokenId)
                .treasury(treasuryId)
                .build();
        txn = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .tokenUpdate(body)
                .build();
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        final var oldRel = writableTokenRelStore.get(treasuryId, fungibleTokenId);
        assertThat(oldRel).isNotNull();
        final var oldToken = writableTokenStore.get(fungibleTokenId);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var newRel = writableTokenRelStore.get(treasuryId, fungibleTokenId);
        assertThat(newRel).isNotNull();

        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(oldToken).isEqualTo(modifiedToken);
        assertThat(oldRel).isEqualTo(newRel);
    }

    @Test
    void followsHappyPathWithNewTreasuryAndZeroBalanceOldTreasury() {
        txn = new TokenUpdateBuilder()
                .withTreasury(payerId)
                .withToken(fungibleTokenId)
                .build();
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        writableAccountStore.put(account.copyBuilder().numberPositiveBalances(0).build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(writableAccountStore);
        assertThat(writableTokenRelStore.get(payerId, fungibleTokenId)).isNull();
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var rel = writableTokenRelStore.get(payerId, fungibleTokenId);

        assertThat(rel).isNotNull();
        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(payerId);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(modifiedToken.tokenType()).isEqualTo(FUNGIBLE_COMMON);

        verify(recordBuilder)
                .addAutomaticTokenAssociation(TokenAssociation.newBuilder()
                        .tokenId(fungibleTokenId)
                        .accountId(payerId)
                        .build());
    }

    @Test
    void doesntGrantKycOrUnfreezeNewTreasuryIfNoKeyIsPresent() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder()
                .withTreasury(payerId)
                .withToken(fungibleTokenId)
                .withKycKey(null)
                .wthFreezeKey(null)
                .build();
        given(handleContext.body()).willReturn(txn);
        writableTokenRelStore.remove(TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .build());
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(writableTokenRelStore);
        assertThat(writableTokenRelStore.get(payerId, fungibleTokenId)).isNull();

        writableTokenStore.put(fungibleToken
                .copyBuilder()
                .kycKey((Key) null)
                .freezeKey((Key) null)
                .build());
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var rel = writableTokenRelStore.get(payerId, fungibleTokenId);

        assertThat(rel).isNotNull();
        final var modifiedToken = writableTokenStore.get(fungibleTokenId);
        assertThat(modifiedToken.symbol()).isEqualTo("TTT");
        assertThat(modifiedToken.name()).isEqualTo("TestToken1");
        assertThat(modifiedToken.treasuryAccountId()).isEqualTo(payerId);
        assertThat(modifiedToken.adminKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.supplyKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.wipeKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.feeScheduleKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.pauseKey()).isEqualTo(B_COMPLEX_KEY);
        assertThat(modifiedToken.autoRenewAccountId()).isEqualTo(ownerId);
        assertThat(modifiedToken.expirationSecond()).isEqualTo(1234600L);
        assertThat(modifiedToken.memo()).isEqualTo("test token1");
        assertThat(modifiedToken.autoRenewSeconds()).isEqualTo(fungibleToken.autoRenewSeconds());
        assertThat(modifiedToken.tokenType()).isEqualTo(FUNGIBLE_COMMON);

        assertThat(rel.frozen()).isFalse();
        assertThat(rel.kycGranted()).isTrue();

        verify(recordBuilder)
                .addAutomaticTokenAssociation(TokenAssociation.newBuilder()
                        .tokenId(fungibleTokenId)
                        .accountId(payerId)
                        .build());
    }

    @Test
    void validatesUpdatingKeys() {
        txn = new TokenUpdateBuilder().withAdminKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ADMIN_KEY));

        txn = new TokenUpdateBuilder().withSupplyKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_SUPPLY_KEY));

        txn = new TokenUpdateBuilder().withWipeKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_WIPE_KEY));

        txn = new TokenUpdateBuilder().withFeeScheduleKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_CUSTOM_FEE_SCHEDULE_KEY));

        txn = new TokenUpdateBuilder().withPauseKey(Key.DEFAULT).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_PAUSE_KEY));
    }

    @Test
    void validateZeroTreasuryNotUpdatedForContractCalls() {
        // We don't set transaction ID so we simulate a contract call
        txn = TransactionBody.newBuilder()
                .tokenUpdate(TokenUpdateTransactionBody.newBuilder()
                        .token(fungibleTokenId)
                        .treasury(zeroAccountId)
                        .name("TestTokenUpdateDoesNotUpdateZeroTreasury")
                        .build())
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var token = writableTokenState.get(fungibleTokenId);
        assertThat(token.treasuryAccountId()).isEqualTo(fungibleToken.treasuryAccountId());
        assertThat(token.treasuryAccountId()).isNotEqualTo(zeroAccountId);
        assertThat(token.name()).isEqualTo("TestTokenUpdateDoesNotUpdateZeroTreasury");
    }

    @Test
    void validateZeroTreasuryIsUpdatedForHapiCalls() {
        given(expiryValidator.resolveUpdateAttempt(any(), any()))
                .willReturn(new ExpiryMeta(1234600L, autoRenewSecs, ownerId));
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        txn = new TokenUpdateBuilder()
                .withToken(fungibleTokenId)
                .withTreasury(zeroAccountId)
                .withName("TestTokenUpdateDoesUpdateZeroTreasury")
                .build();
        given(handleContext.body()).willReturn(txn);
        assertThatNoException().isThrownBy(() -> subject.handle(handleContext));

        final var token = writableTokenState.get(fungibleTokenId);
        assertThat(token.treasuryAccountId()).isEqualTo(zeroAccountId);
        assertThat(token.name()).isEqualTo("TestTokenUpdateDoesUpdateZeroTreasury");
    }

    /* --------------------------------- Helpers --------------------------------- */
    /**
     * A builder for {@link com.hedera.hapi.node.transaction.TransactionBody} instances.
     */
    private class TokenUpdateBuilder {
        private AccountID payer = payerId;
        private AccountID treasury = ownerId;
        private Key adminKey = B_COMPLEX_KEY;
        private String name = "TestToken1";
        private String symbol = "TTT";
        private Key kycKey = B_COMPLEX_KEY;
        private Key freezeKey = B_COMPLEX_KEY;
        private Key wipeKey = B_COMPLEX_KEY;
        private Key supplyKey = B_COMPLEX_KEY;
        private Key feeScheduleKey = B_COMPLEX_KEY;
        private Key pauseKey = B_COMPLEX_KEY;
        private Key metadataKey = B_COMPLEX_KEY;
        private Timestamp expiry = Timestamp.newBuilder().seconds(1234600L).build();
        private AccountID autoRenewAccount = ownerId;
        private long autoRenewPeriod = autoRenewSecs;
        private String memo = "test token1";
        private String metadata = "test metadata";
        private TokenKeyValidation keyVerification = TokenKeyValidation.FULL_VALIDATION;
        TokenID tokenId = fungibleTokenId;

        private TokenUpdateBuilder() {}

        public TransactionBody build() {
            final var transactionID =
                    TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
            final var createTxnBody = TokenUpdateTransactionBody.newBuilder()
                    .token(tokenId)
                    .symbol(symbol)
                    .name(name)
                    .treasury(treasury)
                    .adminKey(adminKey)
                    .supplyKey(supplyKey)
                    .kycKey(kycKey)
                    .freezeKey(freezeKey)
                    .wipeKey(wipeKey)
                    .metadataKey(metadataKey)
                    .metadata(Bytes.wrap(metadata))
                    .feeScheduleKey(feeScheduleKey)
                    .pauseKey(pauseKey)
                    .keyVerificationMode(keyVerification)
                    .autoRenewAccount(autoRenewAccount)
                    .expiry(expiry)
                    .memo(memo);
            if (autoRenewPeriod > 0) {
                createTxnBody.autoRenewPeriod(
                        Duration.newBuilder().seconds(autoRenewPeriod).build());
            }
            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .tokenUpdate(createTxnBody.build())
                    .build();
        }

        public TokenUpdateBuilder withToken(TokenID tokenId) {
            this.tokenId = tokenId;
            return this;
        }

        public TokenUpdateBuilder withFreezeKey(Key freezeKey) {
            this.freezeKey = freezeKey;
            return this;
        }

        public TokenUpdateBuilder withAutoRenewAccount(AccountID autoRenewAccount) {
            this.autoRenewAccount = autoRenewAccount;
            return this;
        }

        public TokenUpdateBuilder withSymbol(final String symbol) {
            this.symbol = symbol;
            return this;
        }

        public TokenUpdateBuilder withName(final String name) {
            this.name = name;
            return this;
        }

        public TokenUpdateBuilder withTreasury(final AccountID treasury) {
            this.treasury = treasury;
            return this;
        }

        public TokenUpdateBuilder withFeeScheduleKey(final Key key) {
            this.feeScheduleKey = key;
            return this;
        }

        public TokenUpdateBuilder withAdminKey(final Key key) {
            this.adminKey = key;
            return this;
        }

        public TokenUpdateBuilder withSupplyKey(final Key key) {
            this.supplyKey = key;
            return this;
        }

        public TokenUpdateBuilder withKycKey(final Key key) {
            this.kycKey = key;
            return this;
        }

        public TokenUpdateBuilder withWipeKey(final Key key) {
            this.wipeKey = key;
            return this;
        }

        public TokenUpdateBuilder withExpiry(final long expiry) {
            this.expiry = Timestamp.newBuilder().seconds(expiry).build();
            return this;
        }

        public TokenUpdateBuilder withAutoRenewPeriod(final long autoRenewPeriod) {
            this.autoRenewPeriod = autoRenewPeriod;
            return this;
        }

        public TokenUpdateBuilder withMemo(final String s) {
            this.memo = s;
            return this;
        }

        public TokenUpdateBuilder withMetadata(final String s) {
            this.metadata = s;
            return this;
        }

        public TokenUpdateBuilder withPauseKey(final Key key) {
            this.pauseKey = key;
            return this;
        }

        public TokenUpdateBuilder withMetadataKey(final Key key) {
            this.metadataKey = key;
            return this;
        }

        public TokenUpdateBuilder wthFreezeKey(final Key key) {
            this.freezeKey = key;
            return this;
        }

        public TokenUpdateBuilder withKeyVerification(final TokenKeyValidation keyVerification) {
            this.keyVerification = keyVerification;
            return this;
        }
    }

    private void setUpTxnContext() {
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
    }
}
