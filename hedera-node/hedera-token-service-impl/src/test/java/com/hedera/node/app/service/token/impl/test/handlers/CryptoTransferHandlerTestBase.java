// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.A_COMPLEX_KEY;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.hapi.node.token.TokenClaimAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenClaimAirdropHandler;
import com.hedera.node.app.service.token.impl.test.handlers.transfer.StepsBase;
import com.hedera.node.app.service.token.impl.util.PendingAirdropUpdater;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.impl.validators.TokenAirdropValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoTransferHandlerTestBase extends StepsBase {

    protected static final AccountID ACCOUNT_ID_3333 = asAccount(0L, 0L, 3333);
    protected static final AccountID ACCOUNT_ID_4444 = asAccount(0L, 0L, 4444);
    protected static final TokenID TOKEN_2468 = asToken(2468);
    protected static final TokenID TOKEN_2469 = asToken(2469);
    protected static final NftID NFT_ID =
            NftID.newBuilder().tokenId(TOKEN_2469).serialNumber(1).build();
    protected static final Account ACCOUNT_3333 =
            Account.newBuilder().accountId(ACCOUNT_ID_3333).key(A_COMPLEX_KEY).build();

    protected static final AccountAmount ACCT_3333_MINUS_10 =
            AccountAmount.newBuilder().accountID(ACCOUNT_ID_3333).amount(-10).build();
    protected static final AccountAmount ACCT_4444_MINUS_5 =
            AccountAmount.newBuilder().accountID(ACCOUNT_ID_4444).amount(-5).build();
    protected static final AccountAmount ACCT_3333_PLUS_5 =
            AccountAmount.newBuilder().accountID(ACCOUNT_ID_3333).amount(5).build();
    protected static final AccountAmount ACCT_4444_PLUS_10 =
            AccountAmount.newBuilder().accountID(ACCOUNT_ID_4444).amount(10).build();
    protected static final NftTransfer SERIAL_1_FROM_3333_TO_4444 = NftTransfer.newBuilder()
            .serialNumber(1)
            .senderAccountID(ACCOUNT_ID_3333)
            .receiverAccountID(ACCOUNT_ID_4444)
            .build();
    protected static final NftTransfer SERIAL_2_FROM_3333_TO_4444 = NftTransfer.newBuilder()
            .serialNumber(2)
            .senderAccountID(ACCOUNT_ID_3333)
            .receiverAccountID(ACCOUNT_ID_4444)
            .build();
    protected static final NftTransfer SERIAL_2_FROM_4444_TO_3333 = NftTransfer.newBuilder()
            .serialNumber(2)
            .senderAccountID(ACCOUNT_ID_4444)
            .receiverAccountID(ACCOUNT_ID_3333)
            .build();

    protected CryptoTransferHandler subject;
    protected TokenAirdropHandler tokenAirdropHandler;
    protected TokenAirdropValidator tokenAirdropValidator;
    protected TokenClaimAirdropHandler tokenClaimAirdropHandler;
    protected CryptoTransferValidator validator;
    protected PendingAirdropUpdater pendingAirdropUpdater;

    @Mock
    protected HandleContext.SavepointStack stack;

    @BeforeEach
    public void setUp() {
        super.setUp();
        validator = new CryptoTransferValidator(new AppEntityIdFactory(configuration));
        tokenAirdropValidator = new TokenAirdropValidator();
        subject = new CryptoTransferHandler(validator);
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);
        pendingAirdropUpdater = new PendingAirdropUpdater();
        tokenClaimAirdropHandler =
                new TokenClaimAirdropHandler(tokenAirdropValidator, validator, pendingAirdropUpdater);
    }

    protected TransactionBody newCryptoTransfer(final AccountAmount... acctAmounts) {
        return newCryptoTransfer(Arrays.stream(acctAmounts).toList(), List.of());
    }

    protected TransactionBody newCryptoTransfer(final TokenTransferList... tokenTransferLists) {
        return newCryptoTransfer(List.of(), Arrays.stream(tokenTransferLists).toList());
    }

    // Note: `tokenTransferLists` can include both fungible and non-fungible token transfers
    protected TransactionBody newCryptoTransfer(
            final List<AccountAmount> acctAmounts, final List<TokenTransferList> tokenTransferLists) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder().accountAmounts(acctAmounts))
                        .tokenTransfers(tokenTransferLists))
                .build();
    }

    protected TransactionBody newTokenAirdrop(final TokenTransferList... tokenTransferLists) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .tokenAirdrop(TokenAirdropTransactionBody.newBuilder().tokenTransfers(tokenTransferLists))
                .build();
    }

    protected TransactionBody newTokenAirdrop(final List<TokenTransferList> tokenTransferLists) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .tokenAirdrop(TokenAirdropTransactionBody.newBuilder().tokenTransfers(tokenTransferLists))
                .build();
    }

    protected TransactionBody newTokenClaimAirdrop(
            final TokenClaimAirdropTransactionBody tokenClaimAirdropTransactionBody) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .tokenClaimAirdrop(tokenClaimAirdropTransactionBody)
                .build();
    }
}
