// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.aggregateApproveNftAllowances;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.getEffectiveOwner;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.isValidOwner;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.AllowanceValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AllowanceValidatorTest extends CryptoTokenHandlerTestBase {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ExpiryValidator expiryValidator;

    @BeforeEach
    public void setUp() {
        super.setUp();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(ownerId, ownerAccount)
                .value(payerId, account)
                .value(spenderId, spenderAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
    }

    @Test
    void aggregatedListCorrectly() {
        List<NftAllowance> list = new ArrayList<>();
        final var Nftid = NftAllowance.newBuilder()
                .spender(spenderId)
                .serialNumbers(List.of(1L, 10L))
                .tokenId(nonFungibleTokenId)
                .owner(ownerId)
                .approvedForAll(Boolean.TRUE)
                .build();
        final var Nftid2 = NftAllowance.newBuilder()
                .spender(spenderId)
                .serialNumbers(List.of(1L, 100L))
                .tokenId(nonFungibleTokenId)
                .owner(ownerId)
                .approvedForAll(Boolean.FALSE)
                .build();
        list.add(Nftid);
        list.add(Nftid2);
        assertThat(aggregateApproveNftAllowances(list)).isEqualTo(4);
    }

    @Test
    void validatesAllowancesLimit() {
        assertThatNoException().isThrownBy(() -> AllowanceValidator.validateAllowanceLimit(ownerAccount, 100));
        assertThatThrownBy(() -> AllowanceValidator.validateAllowanceLimit(ownerAccount, 0))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MAX_ALLOWANCES_EXCEEDED));
    }

    @Test
    void validatesOwner() {
        assertThat(isValidOwner(nftSl1, spenderId, fungibleToken)).isFalse();
        assertThat(isValidOwner(nftSl1, ownerId, nonFungibleToken)).isTrue();
    }

    @Test
    void getsEffectiveOwnerIfOwnerNullOrZero() {
        assertThat(getEffectiveOwner(null, account, readableAccountStore, expiryValidator))
                .isEqualTo(account);
        assertThat(getEffectiveOwner(AccountID.DEFAULT, account, readableAccountStore, expiryValidator))
                .isEqualTo(account);
    }

    @Test
    void getsEffectiveOwnerIfOwnerValid() {
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        assertThat(getEffectiveOwner(ownerId, account, readableAccountStore, expiryValidator))
                .isEqualTo(ownerAccount);
    }

    @Test
    void failsIfEffectiveOwnerDoesntExist() {
        final var missingOwner =
                AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(1000).build();
        assertThatThrownBy(() -> getEffectiveOwner(missingOwner, account, readableAccountStore, expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));
    }

    @Test
    void failsIfEffectiveOwnerIsDeleted() {
        deleteAccount = account.copyBuilder().deleted(true).build();
        readableAccounts = emptyReadableAccountStateBuilder()
                .value(deleteAccountId, deleteAccount)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        assertThatThrownBy(
                        () -> getEffectiveOwner(deleteAccountId, deleteAccount, readableAccountStore, expiryValidator))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ALLOWANCE_OWNER_ID));
    }
}
