package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;

import javax.inject.Inject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllow;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllowPost;
import static com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI.encodeAllowPre;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

public class HookCallFactory {
    @Inject
    public HookCallFactory() {
    }

    public HookInvocations from(HandleContext handleContext,
                                AccountID payer,
                                List<CryptoTransferTransactionBody> userAndAssessedTxns) {
        final var accountStore = handleContext.storeFactory().readableStore(ReadableAccountStore.class);
        final var tokenStore = handleContext.storeFactory().readableStore(ReadableTokenStore.class);
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
            final var ownerAddr = getAddress(accountStore, ownerId);

            if (aa.hasPreTxAllowanceHook()) {
                final var hook = aa.preTxAllowanceHookOrThrow();
                final var hookCall = hook.evmHookCallOrThrow();
                pre.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        false,
                        encodeAllow(ownerAddr, txnFee, BigInteger.valueOf(hookCall.gasLimit()), memo, hookCall.data().toByteArray(), proposedTransfers),
                        hookCall.gasLimit()));
            }
            if(aa.hasPrePostTxAllowanceHook()) {
                final var hook = aa.prePostTxAllowanceHook();
                final var hookCall = hook.evmHookCallOrThrow();
                final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                pre.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        false,
                        encodeAllowPre(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers), hookCall.gasLimit()));
                post.add(new HookInvocation(hook.hookId(),
                        ownerId,
                        true,
                        encodeAllowPost(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers), hookCall.gasLimit()));
            }
        }
        for(final var ttl : userTxn.tokenTransfers()) {
            for(final var aa : ttl.transfers()) {
                final var ownerId = aa.accountIDOrThrow();
                final var ownerAddr = getAddress(accountStore, ownerId);
                if (aa.hasPreTxAllowanceHook()) {
                    final var hook = aa.preTxAllowanceHookOrThrow();
                    final var hookCall = hook.evmHookCallOrThrow();
                    final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                    pre.add(new HookInvocation(hook.hookId(),
                            ownerId,
                            false,
                            encodeAllow(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                            hookCall.gasLimit()));
                }
                if(aa.hasPrePostTxAllowanceHook()) {
                    final var hook = aa.prePostTxAllowanceHook();
                    final var hookCall = hook.evmHookCallOrThrow();
                    final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                    pre.add(new HookInvocation(hook.hookId(),
                            ownerId,
                            false,
                            encodeAllowPre(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                            hookCall.gasLimit()));
                    post.add(new HookInvocation(hook.hookId(),
                            ownerId,
                            true,
                            encodeAllowPost(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                            hookCall.gasLimit()));
                }
            }
            for(final var nft : ttl.nftTransfers()) {
                if(nft.hasPreTxSenderAllowanceHook() || nft.hasPrePostTxSenderAllowanceHook()) {
                    final var ownerId = nft.senderAccountIDOrThrow();
                    final var ownerAddr = getAddress(accountStore, ownerId);
                    if (nft.hasPreTxSenderAllowanceHook()) {
                        final var hook = nft.preTxSenderAllowanceHook();
                        final var hookCall = hook.evmHookCallOrThrow();
                        final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                        pre.add(new HookInvocation(hook.hookId(),
                                ownerId,
                                false,
                                encodeAllow(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                    }
                    if (nft.hasPrePostTxSenderAllowanceHook()) {
                        final var hook = nft.prePostTxSenderAllowanceHook();
                        final var hookCall = hook.evmHookCallOrThrow();
                        final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                        pre.add(new HookInvocation(hook.hookId(),
                                ownerId,
                                false,
                                encodeAllowPre(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                        post.add(new HookInvocation(hook.hookId(),
                                ownerId,
                                true,
                                encodeAllowPost(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                    }
                }
                if(nft.hasPreTxReceiverAllowanceHook() || nft.hasPrePostTxReceiverAllowanceHook()){
                    final var ownerId = nft.receiverAccountIDOrThrow();
                    final var ownerAddr = getAddress(accountStore, ownerId);
                    if (nft.hasPreTxReceiverAllowanceHook()) {
                        final var hook = nft.preTxReceiverAllowanceHook();
                        final var hookCall = hook.evmHookCallOrThrow();
                        final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                        pre.add(new HookInvocation(hook.hookId(),
                                ownerId,
                               false,
                                encodeAllow(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                    }
                    if (nft.hasPrePostTxReceiverAllowanceHook()) {
                        final var hook = nft.prePostTxReceiverAllowanceHook();
                        final var hookCall = hook.evmHookCallOrThrow();
                        final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
                        pre.add(new HookInvocation(hook.hookId(),
                                ownerId,
                                false,
                                encodeAllowPre(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                        post.add(new HookInvocation(hook.hookId(),
                                ownerId,
                               true,
                                encodeAllowPost(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                                hookCall.gasLimit()));
                    }
                }
            }
        }
        return new HookInvocations(pre, post);
    }

    @NonNull
    private static Address getAddress(final ReadableAccountStore accountStore, final AccountID ownerId) {
        final var owner = accountStore.getAccountById(ownerId);
        validateTrue(owner != null, INVALID_ACCOUNT_ID);
        final var ownerAddr = priorityAddressOf(owner);
        return ownerAddr;
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

    /**
     * Given an account, returns its "priority" address as a Besu address.
     *
     * @param account the account
     * @return the priority address
     */
    public static Address priorityAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        return Address.wrap(String.valueOf(Bytes.wrap(explicitAddressOf(account))));
    }


    /**
     * Given an account, returns its explicit 20-byte address.
     *
     * @param account the account
     * @return the explicit 20-byte address
     */
    public static byte[] explicitAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        final var evmAddress = extractEvmAddress(account.alias());
        return evmAddress != null
                ? evmAddress.toByteArray()
                : asEvmAddress(account.accountIdOrThrow().accountNumOrThrow());
    }



}
