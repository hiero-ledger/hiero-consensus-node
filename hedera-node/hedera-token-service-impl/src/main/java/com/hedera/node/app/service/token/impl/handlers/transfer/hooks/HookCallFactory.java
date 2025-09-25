package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;

import javax.inject.Inject;
import java.math.BigInteger;
import java.nio.ByteBuffer;
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
    public static final byte[] HOOK_ADDR_20 = new byte[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0x01, 0x6d};
    public static final com.esaulpaugh.headlong.abi.Address HOOK_ADDR = Address.wrap(String.valueOf(ByteBuffer.wrap(HOOK_ADDR_20)));
    @Inject
    public HookCallFactory() {
    }

    public TransferExecutor.HookInvocations from(HandleContext handleContext,
                                                 List<CryptoTransferTransactionBody> userAndAssessedTxns) {
        final var accountStore = handleContext.storeFactory().readableStore(ReadableAccountStore.class);
        final var tokenStore = handleContext.storeFactory().readableStore(ReadableTokenStore.class);

        final var pre = new ArrayList<HookInvocation>();
        final var post = new ArrayList<HookInvocation>();

        final var memo = handleContext.body().memo();
        final var txnFee = BigInteger.valueOf(handleContext.body().transactionFee());
        final var proposedTransfers = encodeProposedTransfers(userAndAssessedTxns, accountStore, tokenStore);

        final var userTxn = userAndAssessedTxns.get(0);
        final var hbarAAs = userTxn.transfersOrElse(TransferList.DEFAULT).accountAmounts();

        for (final var aa : hbarAAs) {
            final var ownerId = aa.accountIDOrThrow();
            final var ownerAddr = resolveAccountAddress(accountStore, ownerId);
            addAccountAmountHooks(pre, post, aa, ownerId, ownerAddr, memo, txnFee, proposedTransfers);
        }
        for (final var ttl : userTxn.tokenTransfers()) {
            for (final var aa : ttl.transfers()) {
                final var ownerId = aa.accountIDOrThrow();
                final var ownerAddr = resolveAccountAddress(accountStore, ownerId);
                addAccountAmountHooks(pre, post, aa, ownerId, ownerAddr, memo, txnFee, proposedTransfers);
            }
            for (final var nft : ttl.nftTransfers()) {
                if (nft.hasPreTxSenderAllowanceHook() || nft.hasPrePostTxSenderAllowanceHook()) {
                    final var ownerId = nft.senderAccountIDOrThrow();
                    final var ownerAddr = resolveAccountAddress(accountStore, ownerId);
                    addNftHooks(pre, post, nft, true, ownerId, ownerAddr, memo, txnFee, proposedTransfers);
                }
                if (nft.hasPreTxReceiverAllowanceHook() || nft.hasPrePostTxReceiverAllowanceHook()) {
                    final var ownerId = nft.receiverAccountIDOrThrow();
                    final var ownerAddr = resolveAccountAddress(accountStore, ownerId);
                    addNftHooks(pre, post, nft, false, ownerId, ownerAddr, memo, txnFee, proposedTransfers);
                }
            }
        }
        return new TransferExecutor.HookInvocations(pre, post);
    }

    private static void addNftHooks(final List<HookInvocation> pre,
                                    final List<HookInvocation> post,
                                    final NftTransfer nft,
                                    final boolean forSender,
                                    final AccountID ownerId,
                                    final Address ownerAddr,
                                    final String memo,
                                    final BigInteger txnFee,
                                    final Tuple proposedTransfers) {
        if (forSender) {
            if (nft.hasPreTxSenderAllowanceHook()) {
                final var hook = nft.preTxSenderAllowanceHook();
                addPreHook(pre, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
            }
            if (nft.hasPrePostTxSenderAllowanceHook()) {
                final var hook = nft.prePostTxSenderAllowanceHook();
                addPrePostHooks(pre, post, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
            }
        } else {
            if (nft.hasPreTxReceiverAllowanceHook()) {
                final var hook = nft.preTxReceiverAllowanceHook();
                addPreHook(pre, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
            }
            if (nft.hasPrePostTxReceiverAllowanceHook()) {
                final var hook = nft.prePostTxReceiverAllowanceHook();
                addPrePostHooks(pre, post, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
            }
        }
    }
    private static void addAccountAmountHooks(final List<HookInvocation> pre,
                                              final List<HookInvocation> post,
                                              final AccountAmount aa,
                                              final AccountID ownerId,
                                              final Address ownerAddr,
                                              final String memo,
                                              final BigInteger txnFee,
                                              final Tuple proposedTransfers) {
        if (aa.hasPreTxAllowanceHook()) {
            final var hook = aa.preTxAllowanceHookOrThrow();
            addPreHook(pre, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
        }
        if (aa.hasPrePostTxAllowanceHook()) {
            final var hook = aa.prePostTxAllowanceHook();
            addPrePostHooks(pre, post, ownerId, ownerAddr, memo, txnFee, proposedTransfers, hook);
        }
    }

    private static void addPreHook(final List<HookInvocation> pre, final AccountID ownerId, final Address ownerAddr, final String memo, final BigInteger txnFee, final Tuple proposedTransfers, final HookCall hook) {
        final var hookCall = hook.evmHookCallOrThrow();
        final var gasLimit = BigInteger.valueOf(hookCall.gasLimit());
        pre.add(new HookInvocation(hook.hookId(),
                ownerId,
                false,
                encodeAllow(ownerAddr, txnFee, gasLimit, memo, hookCall.data().toByteArray(), proposedTransfers),
                hookCall.gasLimit()));
    }

    private static void addPrePostHooks(final List<HookInvocation> pre,
                                        final List<HookInvocation> post,
                                        final AccountID ownerId,
                                        final Address ownerAddr,
                                        final String memo,
                                        final BigInteger txnFee,
                                        final Tuple proposedTransfers,
                                        final HookCall hook) {
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

    /* ---------- ABI encoding for ProposedTransfers (fixes tuple shapes + isApproval) ---------- */
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
    /**
     * Transfers = (TransferList(AccountAmount[]), TokenTransferList[])
     * where AccountAmount tuple is (address,int64,bool) and NFT tuple is (address,address,int64,bool).
     * NOTE the hbar list is wrapped as a *tuple containing the array* to match your XFER_LIST_TUPLE "( (address,int64,bool)[] )"
     */
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
        final var tokenAddress = toTokenAddress(ttl.tokenOrThrow());
        final var transfers = encodeAccountAmounts(ttl.transfers(), accounts);
        final var nftTransfers = encodeNftTransfers(ttl.nftTransfers(), accounts, tokens);
        return Tuple.of(tokenAddress, transfers, nftTransfers); // (address, AccountAmount[], NftTransfer[])
    }

    private Tuple[] encodeNftTransfers(final List<NftTransfer> nftTransfers,
                                       final ReadableAccountStore accounts,
                                       final ReadableTokenStore tokens) {
        return nftTransfers.stream().map(nft -> {
            final var sender = resolveAccountAddress(accounts, nft.senderAccountIDOrThrow());
            final var receiver = resolveAccountAddress(accounts, nft.receiverAccountIDOrThrow());
            final var serialNum = Long.valueOf(nft.serialNumber());
            return Tuple.of(sender, receiver, serialNum); // (address,address,uint64)
        }).toArray(Tuple[]::new);
    }

    private Tuple[] encodeAccountAmounts(List<AccountAmount> items, ReadableAccountStore accountStore) {
        return items.stream().map(aa -> {
            final var address = resolveAccountAddress(accountStore, aa.accountIDOrThrow());
            final var amount = Long.valueOf(aa.amount());
            return Tuple.of(address, amount); // (address,int64)
        }).toArray(Tuple[]::new);
    }

    /* ---------- Address helpers ---------- */
    private static Address resolveAccountAddress(
            final ReadableAccountStore accountStore, final AccountID ownerId
    ) {
        final var owner = accountStore.getAccountById(ownerId);
        validateTrue(owner != null, INVALID_ACCOUNT_ID);
        return priorityAddressOf(owner);
    }

    /**
     * Given an account, returns its "priority" address as a Besu address.
     *
     * @param account the account
     * @return the priority address
     */
    private static Address priorityAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        return Address.wrap(String.valueOf(Bytes.wrap(explicitAddressOf(account))));
    }


    /**
     * Given an account, returns its explicit 20-byte address.
     *
     * @param account the account
     * @return the explicit 20-byte address
     */
    private static byte[] explicitAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        final var evmAddress = extractEvmAddress(account.alias());
        return evmAddress != null
                ? evmAddress.toByteArray()
                : asEvmAddress(account.accountIdOrThrow().accountNumOrThrow());
    }
    private static Address toTokenAddress(final TokenID tokenId) {
        // Mirror-derive the EVM address for a token; replace if you have a token EVM alias utility
        return Address.wrap(String.valueOf(Bytes.wrap(asEvmAddress(tokenId.tokenNum()))));
    }

    public record HookInvocation(
            long hookId,
            AccountID ownerId,
            boolean isPost,
            byte[] abiEncodedInput,
            long gasLimit) {}
}
