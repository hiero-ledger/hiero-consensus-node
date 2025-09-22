package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Pair;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;

import javax.inject.Inject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllow;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllowPost;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllowPre;

public class HookCallFactory {
    @Inject
    public HookCallFactory() {
    }

    public HookInvocations from(HandleContext handleContext,
                                AccountID payer,
                                List<CryptoTransferTransactionBody> userAndAssessedTxns,
                                ReadableAccountStore accountStore,
                                ReadableTokenStore tokenStore) {
        final var pre = new ArrayList<HookInvocation>();
        final var post = new ArrayList<HookInvocation>();

        // Build proposed transfers
        final var proposedTransfers = encodeProposedTransfers(userAndAssessedTxns, accountStore, tokenStore);

        final var body = handleContext.body();
        final var memo    = body.memo();                // top-level memo
        final var txnFee  = BigInteger.valueOf(body.transactionFee());                  // TODO: fill with actual charged fee
        final var userTxn = userAndAssessedTxns.get(0);
        final var hbarAAs = userTxn.transfersOrElse(TransferList.DEFAULT).accountAmounts();

        for (final var aa : hbarAAs) {
            final var ownerId = aa.accountIDOrThrow();
            final var ownerAddr = asEvmAddress(ownerId.accountNumOrThrow());

            if (aa.hasPreTxAllowanceHook()) {
                final var hook = aa.preTxAllowanceHookOrThrow();
                final var hookCall = hook.evmHookCallOrThrow();
                pre.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        hookCall.gasLimit(),
                        encodeAllow(ownerAddr, txnFee, hookCall.gasLimit(), memo, hookCall.data().toByteArray(), proposedTransfers)));
            }
            if(aa.hasPrePostTxAllowanceHook()) {
                final var hook = aa.prePostTxAllowanceHook();
                final var hookCall = hook.evmHookCallOrThrow();
                pre.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        hookCall.gasLimit(),
                        encodeAllowPre(ownerAddr, txnFee, hookCall.gasLimit(), memo, hookCall.data().toByteArray(), proposedTransfers)));
                post.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        hookCall.gasLimit(),
                        encodeAllowPost(ownerAddr, txnFee, hookCall.gasLimit(), memo, hookCall.data().toByteArray(), proposedTransfers)));
            }
        }
    }

    private Tuple encodeProposedTransfers(final List<CryptoTransferTransactionBody> assessedTxns,
                                          final ReadableAccountStore accountStore,
                                          final ReadableTokenStore tokenStore) {
        final var userTxn = assessedTxns.get(0);
        final var directTransfers = encodeTransfers(
                userTxn.transfersOrElse(TransferList.DEFAULT),
                userTxn.tokenTransfers(),
                accountStore,
                tokenStore);

        // look at all other bodies to find assessed custom fees
        final var assessedHbarTransfers = new ArrayList<AccountAmount>();
        final var assessedTokenTransfers = new ArrayList<TokenTransferList>();
        for (int i = 1; i < assessedTxns.size(); i++) {
            final var txn = assessedTxns.get(i);
            assessedHbarTransfers.addAll(txn.transfersOrElse(TransferList.DEFAULT).accountAmounts());
            assessedTokenTransfers.addAll(txn.tokenTransfers());
        }

        final var assessedTransfers = encodeTransfers(
                new TransferList(assessedHbarTransfers),
                assessedTokenTransfers,
                accountStore,
                tokenStore);
        return Tuple.of(directTransfers, assessedTransfers); // ((TransferList,TokenTransferList[]),(TransferList,TokenTransferList[]))
    }

    private Tuple encodeTransfers(TransferList hbarTransfers,
                                  List<TokenTransferList> tokenTransfersList,
                                  ReadableAccountStore accountStore,
                                  ReadableTokenStore tokenStore) {
        final var hbarXfers = encodeAccountAmounts(hbarTransfers.accountAmounts(), accountStore);
        final var tokenTransfers = tokenTransfersList.stream()
                .map(ttl -> encodeTokenTransfers(ttl, accountStore, tokenStore))
                .toArray(Tuple[]::new);
        return Tuple.of(hbarXfers, tokenTransfers); // (TransferList(AccountAmount[]), TokenTransferList[])

    }

    @NonNull
    private Tuple encodeTokenTransfers(TokenTransferList ttl,
                                       ReadableAccountStore accounts,
                                       ReadableTokenStore tokens) {
        final var tokenAddress = asEvmAddress(ttl.tokenOrThrow().tokenNum());
        final var transfers = encodeAccountAmounts(ttl.transfers(), accounts);
        final var nftTransfers = encodeNftTransfers(ttl.nftTransfers(), accounts, tokens);
        return Tuple.of(tokenAddress, transfers, nftTransfers); // (address, AccountAmount[], NftTransfer[])
    }

    private Tuple[] encodeNftTransfers(final List<NftTransfer> nftTransfers,
                                       final ReadableAccountStore accounts,
                                       final ReadableTokenStore tokens) {
        return nftTransfers.stream().map(nft -> {
            final var sender = asEvmAddress(nft.senderAccountIDOrThrow().accountNumOrThrow());
            final var receiver = asEvmAddress(nft.receiverAccountIDOrThrow().accountNumOrThrow());
            final var serialNum = Long.valueOf(nft.serialNumber());
            return Tuple.of(sender, receiver, serialNum); // (address,address,uint64)
        }).toArray(Tuple[]::new);
    }

    private Tuple[] encodeAccountAmounts(List<AccountAmount> items, ReadableAccountStore accountStore) {
        return items.stream().map(aa -> {
            final var address = asEvmAddress(aa.accountIDOrThrow().accountNumOrThrow());
            final var amount = Long.valueOf(aa.amount());
            return Tuple.of(address, amount); // (address,int64)
        }).toArray(Tuple[]::new);
    }


}
