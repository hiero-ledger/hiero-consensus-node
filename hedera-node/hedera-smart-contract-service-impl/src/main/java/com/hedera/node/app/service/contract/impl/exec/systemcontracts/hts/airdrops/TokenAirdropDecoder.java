// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TokenAirdropDecoder {

    // Tuple indexes
    private static final int TOKEN = 0;
    private static final int TOKEN_TRANSFERS = 1;
    private static final int NFT_AMOUNT = 2;
    private static final int TOKEN_ACCOUNT_ID = 0;
    private static final int TOKEN_AMOUNT = 1;
    private static final int TOKEN_IS_APPROVAL = 2;
    private static final int NFT_SENDER = 0;
    private static final int NFT_RECEIVER = 1;
    private static final int NFT_SERIAL = 2;
    private static final int NFT_IS_APPROVAL = 3;

    // Validation constant
    private static final Long LAST_RESERVED_SYSTEM_ACCOUNT = 1000L;

    @Inject
    public TokenAirdropDecoder() {
        // Dagger2
    }

    public TransactionBody decodeAirdrop(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenAirdropTranslator.TOKEN_AIRDROP.decodeCall(attempt.inputBytes());
        final var transferList = (Tuple[]) call.get(0);
        final var ledgerConfig = attempt.configuration().getConfigData(LedgerConfig.class);
        final var tokenAirdrop = bodyForAirdrop(transferList, attempt, ledgerConfig);
        return TransactionBody.newBuilder().tokenAirdrop(tokenAirdrop).build();
    }

    private TokenAirdropTransactionBody bodyForAirdrop(
            @NonNull final Tuple[] transferList,
            @NonNull final HtsCallAttempt attempt,
            @NonNull final LedgerConfig ledgerConfig) {
        final var transferBuilderList = new ArrayList<TokenTransferList>();
        validateSemantics(transferList, ledgerConfig);
        Arrays.stream(transferList).forEach(transfer -> {
            final var tokenTransferList = TokenTransferList.newBuilder();
            final var token =
                    ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), transfer.get(TOKEN));
            tokenTransferList.token(token);
            final var tokenAmountsTuple = (Tuple[]) transfer.get(TOKEN_TRANSFERS);
            final var nftAmountsTuple = (Tuple[]) transfer.get(NFT_AMOUNT);
            if (tokenAmountsTuple.length > 0) {
                final var aaList = new ArrayList<AccountAmount>();
                Arrays.stream(tokenAmountsTuple).forEach(tokenAmount -> {
                    final var amount = (long) tokenAmount.get(TOKEN_AMOUNT);
                    final var account = attempt.addressIdConverter().convert(tokenAmount.get(TOKEN_ACCOUNT_ID));
                    // Check if the receiver is a system account
                    if (amount > 0) {
                        checkForSystemAccount(account);
                    }
                    final var isApproval = (boolean) tokenAmount.get(TOKEN_IS_APPROVAL);
                    aaList.add(AccountAmount.newBuilder()
                            .amount(amount)
                            .accountID(account)
                            .isApproval(isApproval)
                            .build());
                });
                tokenTransferList.transfers(aaList);
            }
            if (nftAmountsTuple.length > 0) {
                final var nftTransfersList = new ArrayList<NftTransfer>();
                Arrays.stream(nftAmountsTuple).forEach(nftAmount -> {
                    final var serial = (long) nftAmount.get(NFT_SERIAL);
                    final var sender = attempt.addressIdConverter().convert(nftAmount.get(NFT_SENDER));
                    final var receiver = attempt.addressIdConverter().convert(nftAmount.get(NFT_RECEIVER));
                    checkForSystemAccount(receiver);
                    final var isApproval = (boolean) nftAmount.get(NFT_IS_APPROVAL);
                    final var nftTransfer = NftTransfer.newBuilder()
                            .senderAccountID(sender)
                            .receiverAccountID(receiver)
                            .serialNumber(serial)
                            .isApproval(isApproval)
                            .build();
                    nftTransfersList.add(nftTransfer);
                });
                tokenTransferList.nftTransfers(nftTransfersList);
            }
            transferBuilderList.add(tokenTransferList.build());
        });
        return TokenAirdropTransactionBody.newBuilder()
                .tokenTransfers(transferBuilderList)
                .build();
    }

    private void validateSemantics(@NonNull final Tuple[] transferList, @NonNull final LedgerConfig ledgerConfig) {
        var fungibleBalanceChanges = 0;
        var nftBalanceChanges = 0;
        for (final var airdrop : transferList) {
            fungibleBalanceChanges += ((Tuple[]) airdrop.get(1)).length;
            nftBalanceChanges += ((Tuple[]) airdrop.get(2)).length;
            validateFalse(
                    fungibleBalanceChanges > ledgerConfig.tokenTransfersMaxLen(),
                    TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED);
            validateFalse(
                    nftBalanceChanges > ledgerConfig.nftTransfersMaxLen(), TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED);
        }
    }

    private void checkForSystemAccount(@NonNull final AccountID account) {
        validateFalse(account.accountNumOrThrow() <= LAST_RESERVED_SYSTEM_ACCOUNT, INVALID_RECEIVING_NODE_ACCOUNT);
    }
}
