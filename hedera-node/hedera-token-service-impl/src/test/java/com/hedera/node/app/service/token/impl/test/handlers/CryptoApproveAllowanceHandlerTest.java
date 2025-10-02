// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_DELEGATING_SPENDER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoApproveAllowanceHandlerTest extends CryptoTokenHandlerTestBase {

    @Mock(strictness = Strictness.LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = Strictness.LENIENT)
    private ExpiryValidator expiryValidator;

    @Mock
    private PureChecksContext pureChecksContext;

    private CryptoApproveAllowanceHandler subject;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        final var validator = new ApproveAllowanceValidator();
        givenStoresAndConfig(handleContext);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.payer()).willReturn(payerId);

        subject = new CryptoApproveAllowanceHandler(validator);
    }

    @Test
    void cryptoApproveAllowanceVanilla() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(ownerKey);
    }

    @Test
    void cryptoApproveAllowanceFailsWithInvalidOwner() throws PreCheckException {
        readableAccounts =
                emptyReadableAccountStateBuilder().value(payerId, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ALLOWANCE_OWNER_ID);
    }

    @Test
    void cryptoApproveAllowanceFailsWithInvalidSpenderCrypto() throws PreCheckException {
        readableAccounts =
                emptyReadableAccountStateBuilder().value(payerId, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var allowance =
                cryptoAllowance.copyBuilder().spender((AccountID) null).build();

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(allowance), List.of(tokenAllowance), List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ALLOWANCE_SPENDER_ID);
    }

    @Test
    void cryptoApproveAllowanceFailsWithInvalidSpenderToken() throws PreCheckException {
        readableAccounts =
                emptyReadableAccountStateBuilder().value(payerId, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var allowance =
                tokenAllowance.copyBuilder().spender((AccountID) null).build();

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(allowance), List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ALLOWANCE_SPENDER_ID);
    }

    @Test
    void cryptoApproveAllowanceFailsWithInvalidSpenderNFT() throws PreCheckException {
        readableAccounts =
                emptyReadableAccountStateBuilder().value(payerId, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var allowance =
                nftAllowance.copyBuilder().spender((AccountID) null).build();

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(allowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ALLOWANCE_SPENDER_ID);
    }

    @Test
    void cryptoApproveAllowanceDoesntAddIfOwnerSameAsPayer() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                ownerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 0);
        assertEquals(ownerKey, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void cryptoApproveAllowanceAddsDelegatingSpender() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, true, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(ownerKey);
    }

    @Test
    void cryptoApproveAllowanceFailsIfDelegatingSpenderMissing() throws PreCheckException {
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(payerId, account)
                .value(ownerId, ownerAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, true, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_DELEGATING_SPENDER);
    }

    @Test
    void tokenAllowanceFailsIfOwnerHasAlias() throws PreCheckException {
        final var tokenAllowance = TokenAllowance.newBuilder()
                .tokenId(fungibleTokenId)
                .owner(AccountID.newBuilder()
                        .alias(Bytes.wrap(new byte[] {0, 1, 2}))
                        .build())
                .build();
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, true, Collections.emptyList(), List.of(tokenAllowance), Collections.emptyList());
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ALLOWANCE_OWNER_ID);
    }

    @Test
    void happyPathAddsAllowances() {
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .cryptoAllowances(List.of())
                .tokenAllowances(List.of())
                .approveForAllNftAllowances(List.of())
                .build());
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowanceWithApproveForALl));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).isEmpty();
        assertThat(existingOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).hasSize(1);
        assertThat(modifiedOwner.tokenAllowances()).hasSize(1);
        assertThat(modifiedOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(modifiedOwner.cryptoAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(modifiedOwner.cryptoAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(modifiedOwner.tokenAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).tokenId()).isEqualTo(fungibleTokenId);
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).spenderId())
                .isEqualTo(spenderId);
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).tokenId()).isEqualTo(nonFungibleTokenId);

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void happyPathForUpdatingAllowances() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(existingOwner.cryptoAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(existingOwner.cryptoAllowances().get(0).amount()).isEqualTo(1000);
        assertThat(existingOwner.tokenAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(existingOwner.tokenAllowances().get(0).amount()).isEqualTo(1000);
        assertThat(existingOwner.tokenAllowances().get(0).tokenId()).isEqualTo(fungibleTokenId);
        assertThat(existingOwner.approveForAllNftAllowances().get(0).spenderId())
                .isEqualTo(spenderId);
        assertThat(existingOwner.approveForAllNftAllowances().get(0).tokenId()).isEqualTo(nonFungibleTokenId);

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).hasSize(1);
        assertThat(modifiedOwner.tokenAllowances()).hasSize(1);
        assertThat(modifiedOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(modifiedOwner.cryptoAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(modifiedOwner.cryptoAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).spenderId()).isEqualTo(spenderId);
        assertThat(modifiedOwner.tokenAllowances().get(0).amount()).isEqualTo(10);
        assertThat(modifiedOwner.tokenAllowances().get(0).tokenId()).isEqualTo(fungibleTokenId);
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).spenderId())
                .isEqualTo(spenderId);
        assertThat(modifiedOwner.approveForAllNftAllowances().get(0).tokenId()).isEqualTo(nonFungibleTokenId);

        assertThat(writableNftStore.get(nftIdSl1)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2)).isNotNull();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void settingApproveForAllToFalseRemovesAllowance() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(),
                List.of(),
                List.of(nftAllowance.copyBuilder().approvedForAll(Boolean.FALSE).build()));
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);

        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(modifiedOwner.approveForAllNftAllowances()).isEmpty();
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void failsIfSenderDoesntOwnNFTSerial() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(tokenAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(nftAllowance.copyBuilder().owner(AccountID.DEFAULT).build()));

        given(handleContext.body()).willReturn(txn);
        final var payer = writableAccountStore.getAccountById(this.payerId);

        assertThat(payer.cryptoAllowances()).isEmpty();
        assertThat(payer.tokenAllowances()).isEmpty();
        assertThat(payer.approveForAllNftAllowances()).isEmpty();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));
    }

    @Test
    void treatsPayerAsOwnerIfOwnerNotSet() {
        // change the state to have the payer as owner for NFTs for a passing test.
        // If not it fails since those NFTs are not owned by the payer.
        writableNftState = emptyWritableNftStateBuilder()
                .value(nftIdSl1, nftSl1.copyBuilder().ownerId(payerId).build())
                .value(nftIdSl2, nftSl2.copyBuilder().ownerId(payerId).build())
                .build();
        given(writableStates.<NftID, Nft>get(NFTS_STATE_ID)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates, writableEntityCounters);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(tokenAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(nftAllowance.copyBuilder().owner(AccountID.DEFAULT).build()));

        given(handleContext.body()).willReturn(txn);
        final var payer = writableAccountStore.getAccountById(this.payerId);

        assertThat(payer.cryptoAllowances()).isEmpty();
        assertThat(payer.tokenAllowances()).isEmpty();
        assertThat(payer.approveForAllNftAllowances()).isEmpty();

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));

        final var modifiedPayer = writableAccountStore.getAccountById(this.payerId);

        final var newCryptoAllowances = modifiedPayer.cryptoAllowances();
        final var newTokenAllowances = modifiedPayer.tokenAllowances();
        final var newNftAllowances = modifiedPayer.approveForAllNftAllowances();

        assertThat(newCryptoAllowances).hasSize(1);
        assertThat(newTokenAllowances).hasSize(1);
        assertThat(newNftAllowances).isEmpty();

        assertThat(newCryptoAllowances.get(0).spenderId()).isEqualTo(spenderId);
        assertThat(newTokenAllowances.get(0).spenderId()).isEqualTo(spenderId);
    }

    @Test
    void checksIfAllowancesExceedLimit() {
        final var configOverride = HederaTestConfigBuilder.create()
                .withValue("hedera.allowances.maxAccountLimit", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configOverride);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        given(handleContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void emptyAllowanceListInTransactionFails() throws PreCheckException {
        final var txn = cryptoApproveAllowanceTransaction(payerId, false, List.of(), List.of(), List.of());
        given(handleContext.body()).willReturn(txn);
        // Two know accounts we are using for these tests. Initial allowances
        final var existingPayer = writableAccountStore.getAccountById(payerId);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(existingPayer.cryptoAllowances()).isEmpty();
        assertThat(existingPayer.tokenAllowances()).isEmpty();
        assertThat(existingPayer.approveForAllNftAllowances()).isEmpty();
        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);
        assertThat(existingOwner.approveForAllNftAllowances()).hasSize(1);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_ALLOWANCES));

        // After handle allowances are not modified
        final var afterHandlePayer = writableAccountStore.getAccountById(payerId);
        final var afterHandleOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(afterHandlePayer.cryptoAllowances()).isEmpty();
        assertThat(afterHandlePayer.tokenAllowances()).isEmpty();
        assertThat(afterHandlePayer.approveForAllNftAllowances()).isEmpty();
        assertThat(afterHandleOwner.cryptoAllowances()).hasSize(1);
        assertThat(afterHandleOwner.tokenAllowances()).hasSize(1);
        assertThat(afterHandleOwner.approveForAllNftAllowances()).hasSize(1);
    }

    @Test
    void newAllowancesWithAmountZeroAreNotAdded() {
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .cryptoAllowances(List.of())
                .tokenAllowances(List.of())
                .approveForAllNftAllowances(List.of())
                .build());
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().amount(0).build()),
                List.of(tokenAllowance.copyBuilder().amount(0).build()),
                List.of());
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).isEmpty();
        assertThat(existingOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).isEmpty();
        assertThat(modifiedOwner.tokenAllowances()).isEmpty();
        assertThat(existingOwner.approveForAllNftAllowances()).isEmpty();
    }

    @Test
    void validateNegativeAmounts() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(CryptoAllowance.newBuilder()
                        .amount(-1L)
                        .owner(ownerId)
                        .spender(spenderId)
                        .build()),
                List.of(tokenAllowance),
                List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(NEGATIVE_ALLOWANCE_AMOUNT));

        cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(TokenAllowance.newBuilder()
                        .amount(-1L)
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(fungibleTokenId)
                        .build()),
                List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(NEGATIVE_ALLOWANCE_AMOUNT));
    }

    @Test
    void validateInvalidNftSpender() {
        // NftAllowance with no spender
        final var invalidNftAllowance =
                NftAllowance.newBuilder().tokenId(nonFungibleTokenId).build();
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, Collections.emptyList(), Collections.emptyList(), List.of(invalidNftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_ALLOWANCE_SPENDER_ID);
    }

    @Test
    void validateInvalidTokenAllowanceAmount() {
        // TokenAllowance with invalid allowance amount
        final var invalidTokenAllowance = TokenAllowance.newBuilder()
                .tokenId(fungibleTokenId)
                .spender(spenderId)
                .amount(-1)
                .build();
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, Collections.emptyList(), List.of(invalidTokenAllowance), Collections.emptyList());
        given(pureChecksContext.body()).willReturn(txn);

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), NEGATIVE_ALLOWANCE_AMOUNT);
    }

    @Test
    void validateValidNftAllowance() {
        final var validNftAllowance = NftAllowance.newBuilder()
                .spender(spenderId)
                .tokenId(nonFungibleTokenId)
                .build();
        final var txn = cryptoApproveAllowanceTransaction(
                payerId, false, Collections.emptyList(), Collections.emptyList(), List.of(validNftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext)); // No exception thrown
    }

    @Test
    void existingAllowancesDeletedWithAmountZero() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().amount(0).build()),
                List.of(tokenAllowance.copyBuilder().amount(0).build()),
                List.of());
        given(handleContext.body()).willReturn(txn);
        final var existingOwner = writableAccountStore.getAccountById(ownerId);

        assertThat(existingOwner.cryptoAllowances()).hasSize(1);
        assertThat(existingOwner.tokenAllowances()).hasSize(1);

        subject.handle(handleContext);

        final var modifiedOwner = writableAccountStore.getAccountById(ownerId);
        assertThat(modifiedOwner.cryptoAllowances()).isEmpty();
        assertThat(modifiedOwner.tokenAllowances()).isEmpty();
    }

    @Test
    void handlePayerAccountNotFound() {
        given(handleContext.payer()).willReturn(AccountID.DEFAULT);
        Assertions.assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_PAYER_ACCOUNT_ID));
    }

    @Test
    void calculateFeesHappyPath() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                Timestamp.newBuilder().seconds(account.expirationSecond() - 1).build(),
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowance));
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);
        given(feeCtx.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(feeCtx.payer()).willReturn(payerId);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);
        given(feeCalc.addBytesPerTransaction(anyLong())).willReturn(feeCalc);
        given(feeCalc.addRamByteSeconds(anyLong())).willReturn(feeCalc);
        // The fees wouldn't be free in this scenario, but we don't care about the actual return
        // value here since we're using a mock calculator
        given(feeCalc.calculate()).willReturn(Fees.FREE);

        subject.calculateFees(feeCtx);

        verify(feeCalc).addBytesPerTransaction(128);
        verify(feeCalc).addRamByteSeconds(112);
    }

    @Test
    void calculateFeesAccountNotFound() {
        final var txn = cryptoApproveAllowanceTransaction(
                payerId,
                Timestamp.newBuilder().seconds(account.expirationSecond() - 1).build(),
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowance));
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);
        given(feeCtx.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(feeCtx.payer())
                .willReturn(AccountID.newBuilder().accountNum(Long.MAX_VALUE).build());

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);
        given(feeCalc.addBytesPerTransaction(anyLong())).willReturn(feeCalc);
        given(feeCalc.addRamByteSeconds(anyLong())).willReturn(feeCalc);
        // The fees wouldn't be free in this scenario, but we don't care about the actual return
        // value here since we're using a mock calculator
        given(feeCalc.calculate()).willReturn(Fees.FREE);

        subject.calculateFees(feeCtx);

        verify(feeCalc).addBytesPerTransaction(128);
        verify(feeCalc).addRamByteSeconds(0);
    }

    @Test
    void updateSpenderWithEmptySerialNumsDoesntUpdate() {
        subject.updateSpender(
                writableTokenStore, writableNftStore, ownerAccount, spenderId, nonFungibleTokenId, List.of());
        assertThat(writableTokenStore.modifiedTokens()).isEmpty();
    }

    private TransactionBody cryptoApproveAllowanceTransaction(
            final AccountID id,
            final boolean isWithDelegatingSpender,
            final List<CryptoAllowance> cryptoAllowance,
            final List<TokenAllowance> tokenAllowance,
            final List<NftAllowance> nftAllowance) {
        return cryptoApproveAllowanceTransaction(
                id, consensusTimestamp, isWithDelegatingSpender, cryptoAllowance, tokenAllowance, nftAllowance);
    }

    private TransactionBody cryptoApproveAllowanceTransaction(
            final AccountID id,
            final Timestamp validStart,
            final boolean isWithDelegatingSpender,
            final List<CryptoAllowance> cryptoAllowance,
            final List<TokenAllowance> tokenAllowance,
            final List<NftAllowance> nftAllowance) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(validStart);
        final var allowanceTxnBody = CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(cryptoAllowance)
                .tokenAllowances(tokenAllowance)
                .nftAllowances(isWithDelegatingSpender ? List.of(nftAllowanceWithDelegatingSpender) : nftAllowance)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoApproveAllowance(allowanceTxnBody)
                .build();
    }
}
