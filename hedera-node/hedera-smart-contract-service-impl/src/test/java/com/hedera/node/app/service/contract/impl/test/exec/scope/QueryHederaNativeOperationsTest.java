// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_FUNGIBLE_RELATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CIVILIAN_OWNED_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MOCK_VERIFICATION_STRATEGY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NFT_SERIAL_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.spi.key.KeyVerifier.NO_AUTHORIZING_KEYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaNativeOperations;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryHederaNativeOperationsTest {
    @Mock
    private QueryContext context;

    @Mock
    private MessageFrame frame;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private ReadableNftStore nftStore;

    @Mock
    private ReadableTokenRelationStore relationStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    private QueryHederaNativeOperations subject;

    private AccountID deletedAccount;

    private AccountID fromAccount;
    private AccountID beneficiaryAccount;

    @BeforeEach
    void setUp() {
        subject = new QueryHederaNativeOperations(context, entityIdFactory);
        deletedAccount = AccountID.newBuilder().accountNum(1L).build();
        fromAccount = AccountID.newBuilder().accountNum(3L).build();
        beneficiaryAccount = AccountID.newBuilder().accountNum(2L).build();
    }

    @Test
    void doesNotSupportAnyMutations() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.checkForCustomFees(CryptoTransferTransactionBody.DEFAULT));
        assertThrows(UnsupportedOperationException.class, () -> subject.setNonce(1L, 2L));
        assertThrows(UnsupportedOperationException.class, () -> subject.createHollowAccount(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.finalizeHollowAccountAsContract(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.finalizeHollowAccountAsContract(Bytes.EMPTY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.transferWithReceiverSigCheck(
                        1L, fromAccount, beneficiaryAccount, MOCK_VERIFICATION_STRATEGY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.trackSelfDestructBeneficiary(deletedAccount, beneficiaryAccount, frame));
        assertFalse(subject.canScheduleContractCall(123L, 456L, AccountID.DEFAULT));
    }

    @Test
    void getAccountUsesContextReadableStore() {
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(Account.DEFAULT);
        assertSame(Account.DEFAULT, subject.getAccount(NON_SYSTEM_ACCOUNT_ID));
    }

    @Test
    void resolveAliasReturnsMissingNumIfNotPresent() {
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        assertEquals(MISSING_ENTITY_NUMBER, subject.resolveAlias(0, 0, tuweniToPbjBytes(EIP_1014_ADDRESS)));
    }

    @Test
    void resolveAliasReturnsNumIfPresent() {
        final var alias = tuweniToPbjBytes(EIP_1014_ADDRESS);
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountIDByAlias(0, 0, alias)).willReturn(NON_SYSTEM_ACCOUNT_ID);
        assertEquals(NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), subject.resolveAlias(0, 0, alias));
    }

    @Test
    void getTokenUsesStore() {
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(tokenStore.get(FUNGIBLE_TOKEN_ID)).willReturn(FUNGIBLE_TOKEN);
        assertSame(FUNGIBLE_TOKEN, subject.getToken(FUNGIBLE_TOKEN_ID));
    }

    @Test
    void getRelationshipUsesStore() {
        given(context.createStore(ReadableTokenRelationStore.class)).willReturn(relationStore);
        given(relationStore.get(A_NEW_ACCOUNT_ID, FUNGIBLE_TOKEN_ID)).willReturn(A_FUNGIBLE_RELATION);
        assertSame(A_FUNGIBLE_RELATION, subject.getTokenRelation(A_NEW_ACCOUNT_ID, FUNGIBLE_TOKEN_ID));
    }

    @Test
    void getNftUsesStore() {
        given(context.createStore(ReadableNftStore.class)).willReturn(nftStore);
        given(nftStore.get(CIVILIAN_OWNED_NFT.nftIdOrThrow())).willReturn(CIVILIAN_OWNED_NFT);
        assertSame(CIVILIAN_OWNED_NFT, subject.getNft(NON_FUNGIBLE_TOKEN_ID, NFT_SERIAL_NO));
    }

    @Test
    void authorizingSimpleKeysTest() {
        assertSame(NO_AUTHORIZING_KEYS, subject.authorizingSimpleKeys());
    }
}
