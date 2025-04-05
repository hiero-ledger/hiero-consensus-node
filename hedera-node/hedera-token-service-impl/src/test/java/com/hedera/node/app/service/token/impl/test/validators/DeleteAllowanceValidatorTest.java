// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.getEffectiveOwner;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.DeleteAllowanceValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteAllowanceValidatorTest extends CryptoTokenHandlerTestBase {
    private DeleteAllowanceValidator subject;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ExpiryValidator expiryValidator;

    @BeforeEach
    public void setUp() {
        super.setUp();
        givenStoresAndConfig(handleContext);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        subject = new DeleteAllowanceValidator();
    }

    @Test
    void rejectsMissingToken() {
        final var missingToken = TokenID.newBuilder().tokenNum(10000).build();
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, missingToken, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_ID));
    }

    @Test
    void failsForFungibleToken() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, fungibleTokenId, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES));
    }

    @Test
    void validatesIfOwnerExists() {
        final var missingOwner = AccountID.newBuilder().accountNum(10000).build();
        final var txn = cryptoDeleteAllowanceTransaction(payerId, missingOwner, nonFungibleTokenId, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));
    }

    @Test
    void considersPayerIfOwnerMissing() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, null, nonFungibleTokenId, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatNoException()
                .isThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore));
        assertThat(getEffectiveOwner(null, account, readableAccountStore, mock(ExpiryValidator.class)))
                .isEqualTo(account);
    }

    @Test
    void failsIfTokenNotAssociatedToAccount() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, spenderId, nonFungibleTokenId, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    @Test
    void validatesTotalAllowancesInTxn() {
        // each serial number is considered as one allowance
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, nonFungibleTokenId, List.of(1L, 2L));
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("hedera.allowances.maxTransactionLimit", 1)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();
        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void happyPath() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, nonFungibleTokenId, List.of(1L, 2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();

        assertThatNoException()
                .isThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore));
    }

    @Test
    void validateSerialsExistence() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, nonFungibleTokenId, List.of(1L, 2L, 100L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();

        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void aggregatesSerialsCorrectly() {
        final var allowances = List.of(
                NftRemoveAllowance.newBuilder()
                        .owner(ownerId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(List.of(2L, 3L))
                        .build(),
                NftRemoveAllowance.newBuilder()
                        .owner(ownerId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(List.of(2L, 100L))
                        .build());
        assertThat(DeleteAllowanceValidator.aggregateNftDeleteAllowances(allowances))
                .isEqualTo(4);
    }

    @Test
    void validatesNegativeSerialsAreNotValid() {
        final var txn = cryptoDeleteAllowanceTransaction(payerId, ownerId, nonFungibleTokenId, List.of(1L, -2L));
        given(handleContext.configuration()).willReturn(configuration);
        final var nftAllowances = txn.cryptoDeleteAllowance().nftAllowances();

        assertThatThrownBy(() -> subject.validate(handleContext, nftAllowances, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    private TransactionBody cryptoDeleteAllowanceTransaction(
            final AccountID id, final AccountID ownerId, final TokenID nonFungibleTokenId, final List<Long> serials) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(consensusTimestamp);
        final var allowanceTxnBody = CryptoDeleteAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftRemoveAllowance.newBuilder()
                        .owner(ownerId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(serials)
                        .build())
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoDeleteAllowance(allowanceTxnBody)
                .build();
    }
}
