// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import java.util.List;

public class TransferUtil {
    public static TransferList asTransferList(final long payerCredit, final AccountID payer) {
        return TransferList.newBuilder()
                .accountAmounts(AccountAmount.newBuilder()
                        .accountID(payer)
                        .amount(payerCredit)
                        .build())
                .build();
    }

    public static TokenTransferList asTokenTransferList(
            final TokenID tokenId, final long payerCredit, final AccountID payer) {
        return TokenTransferList.newBuilder()
                .token(tokenId)
                .transfers(AccountAmount.newBuilder()
                        .accountID(payer)
                        .amount(payerCredit)
                        .build())
                .build();
    }

    public static TokenTransferList asTokenTransferList(final TokenID tokenId, List<AccountAmount> accountAmounts) {
        return TokenTransferList.newBuilder()
                .token(tokenId)
                .transfers(accountAmounts)
                .build();
    }

    public static AccountAmount asAccountAmount(final AccountID account, final long amount) {
        return AccountAmount.newBuilder().accountID(account).amount(amount).build();
    }

    public static TokenTransferList asNftTransferList(
            final TokenID nonFungibleTokenId,
            final AccountID sender,
            final AccountID receiver,
            final long serialNumber) {
        final var nftTransfer = asNftTransfer(sender, receiver, serialNumber);
        return TokenTransferList.newBuilder()
                .token(nonFungibleTokenId)
                .nftTransfers(nftTransfer)
                .build();
    }

    private static NftTransfer asNftTransfer(
            final AccountID sender, final AccountID receiver, final long serialNumber) {
        return NftTransfer.newBuilder()
                .senderAccountID(sender)
                .receiverAccountID(receiver)
                .serialNumber(serialNumber)
                .build();
    }
}
