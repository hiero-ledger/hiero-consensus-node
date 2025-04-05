// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ApproveAllowanceValidatorTest extends CryptoTokenHandlerTestBase {
    private ApproveAllowanceValidator subject;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ExpiryValidator expiryValidator;

    @BeforeEach
    public void setUp() {
        super.setUp();
        givenStoresAndConfig(handleContext);
        givenExpiryValidator(handleContext, expiryValidator);

        subject = new ApproveAllowanceValidator();
    }

    @Test
    void returnsValidationOnceFailed() {
        // each serial number is considered as one allowance for nft allowances
        givenApproveAllowanceTxn(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("hedera.allowances.maxTransactionLimit", 1)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configuration);
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void succeedsWithEmptyLists() {
        givenApproveAllowanceTxn(payerId, false, List.of(), List.of(tokenAllowance), List.of(nftAllowance));

        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));

        givenApproveAllowanceTxn(payerId, false, List.of(cryptoAllowance), List.of(), List.of(nftAllowance));

        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));

        givenApproveAllowanceTxn(payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of());

        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));
    }

    @Test
    void validatesSpenderSameAsOwner() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().spender(ownerId).build()),
                List.of(tokenAllowance),
                List.of(nftAllowance));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SPENDER_ACCOUNT_SAME_AS_OWNER));

        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowance.copyBuilder().spender(ownerId).build()));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SPENDER_ACCOUNT_SAME_AS_OWNER));
    }

    @Test
    void allowsSelfApprovalForFungibleTokens() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance.copyBuilder().spender(ownerId).build()),
                List.of(nftAllowance));

        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));
    }

    @Test
    void failsWhenExceedsMaxTokenSupply() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(TokenAllowance.newBuilder()
                        .amount(100001)
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(fungibleTokenId)
                        .build()),
                List.of(nftAllowance));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY));
    }

    @Test
    void failsForNftInFungibleTokenAllowances() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(TokenAllowance.newBuilder()
                        .amount(10)
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(nonFungibleTokenId)
                        .build()),
                List.of(nftAllowance));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES));
    }

    @Test
    void cannotGrantApproveForAllWhenDelegatingSpenderIsSet() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(),
                List.of(),
                List.of(nftAllowance
                        .copyBuilder()
                        .owner(ownerId)
                        .spender(spenderId)
                        .delegatingSpender(delegatingSpenderId)
                        .approvedForAll(true)
                        .build()));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL));
    }

    @Test
    void canGrantNftSerialAllowanceIfDelegatingSpenderHasNoApproveForAllAllowance() {
        assertThat(ownerAccount.approveForAllNftAllowances())
                .contains(AccountApprovalForAllAllowance.newBuilder()
                        .spenderId(spenderId)
                        .tokenId(nonFungibleTokenId)
                        .build());

        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(),
                List.of(),
                List.of(nftAllowance
                        .copyBuilder()
                        .delegatingSpender(delegatingSpenderId)
                        .build()));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL));
    }

    @Test
    void cannotGrantNftSerialAllowanceIfDelegatingSpenderHasNoApproveForAllAllowance() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(),
                List.of(),
                List.of(nftAllowance
                        .copyBuilder()
                        .spender(payerId)
                        .delegatingSpender(transferAccountId)
                        .build()));

        assertThat(readableAccountStore.getAccountById(ownerId).approveForAllNftAllowances())
                .doesNotContain(AccountApprovalForAllAllowance.newBuilder()
                        .spenderId(payerId)
                        .tokenId(nonFungibleTokenId)
                        .build());

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL));
    }

    @Test
    void failsWhenTokenNotAssociatedToAccount() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance.copyBuilder().owner(delegatingSpenderId).build()),
                List.of());
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));

        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowance.copyBuilder().owner(delegatingSpenderId).build()));
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    @Test
    void happyPath() {
        givenApproveAllowanceTxn(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));
    }

    @Test
    void fungibleInNFTAllowances() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(NftAllowance.newBuilder()
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(fungibleTokenId)
                        .approvedForAll(Boolean.TRUE)
                        .build()));

        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES));
    }

    @Test
    void validateSerialsExistence() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(NftAllowance.newBuilder()
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(List.of(1L, 2L, 3L))
                        .build()));

        given(handleContext.configuration()).willReturn(configuration);
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void validateNegativeSerials() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(NftAllowance.newBuilder()
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(List.of(1L, 2L, -3L))
                        .build()));

        given(handleContext.configuration()).willReturn(configuration);
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void validatesAndFiltersRepeatedSerials() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(NftAllowance.newBuilder()
                        .owner(ownerId)
                        .spender(spenderId)
                        .tokenId(nonFungibleTokenId)
                        .serialNumbers(List.of(1L, 2L, 2L, 1L))
                        .build()));

        given(handleContext.configuration()).willReturn(configuration);
        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));
    }

    @Test
    void validatesTotalAllowancesInTxn() {
        // each serial number is considered as one allowance
        givenApproveAllowanceTxn(
                payerId, false, List.of(cryptoAllowance), List.of(tokenAllowance), List.of(nftAllowance));
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("hedera.allowances.maxTransactionLimit", 1)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(configuration);
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void validatesMissingOwnerAccount() {
        final var missingOwner = AccountID.newBuilder().accountNum(1_234L).build();
        final var missingCryptoAllowance = CryptoAllowance.newBuilder()
                .owner(missingOwner)
                .spender(spenderId)
                .amount(10L)
                .build();
        final var missingTokenAllowance = TokenAllowance.newBuilder()
                .owner(missingOwner)
                .spender(spenderId)
                .amount(10L)
                .tokenId(fungibleTokenId)
                .build();
        final var missingNftAllowance = NftAllowance.newBuilder()
                .owner(missingOwner)
                .spender(spenderId)
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of(1L))
                .build();
        givenApproveAllowanceTxn(payerId, false, List.of(missingCryptoAllowance), List.of(), List.of());
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));

        givenApproveAllowanceTxn(payerId, false, List.of(), List.of(missingTokenAllowance), List.of());
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));

        givenApproveAllowanceTxn(payerId, false, List.of(), List.of(), List.of(missingNftAllowance));
        assertThatThrownBy(() -> subject.validate(handleContext, account, readableAccountStore))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));
    }

    @Test
    void considersPayerIfOwnerMissing() {
        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of(),
                List.of());
        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));

        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance.copyBuilder().owner(AccountID.DEFAULT).build()),
                List.of());
        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));

        givenApproveAllowanceTxn(
                payerId,
                false,
                List.of(cryptoAllowance),
                List.of(tokenAllowance),
                List.of(nftAllowance.copyBuilder().owner(AccountID.DEFAULT).build()));
        assertThatNoException().isThrownBy(() -> subject.validate(handleContext, account, readableAccountStore));
    }

    private TransactionBody givenApproveAllowanceTxn(
            final AccountID id,
            final boolean isWithDelegatingSpender,
            final List<CryptoAllowance> cryptoAllowance,
            final List<TokenAllowance> tokenAllowance,
            final List<NftAllowance> nftAllowance) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(consensusTimestamp);
        final var allowanceTxnBody = CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(cryptoAllowance)
                .tokenAllowances(tokenAllowance)
                .nftAllowances(isWithDelegatingSpender ? List.of(nftAllowanceWithDelegatingSpender) : nftAllowance)
                .build();
        final var txn = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoApproveAllowance(allowanceTxnBody)
                .build();
        given(handleContext.body()).willReturn(txn);
        return txn;
    }

    private void givenExpiryValidator(final HandleContext handleContext, final ExpiryValidator expiryValidator) {
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
    }
}
