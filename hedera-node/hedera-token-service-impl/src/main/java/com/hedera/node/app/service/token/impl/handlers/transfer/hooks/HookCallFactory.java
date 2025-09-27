// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.hooks;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

public class HookCallFactory {
    private static final Logger log = LoggerFactory.getLogger(HookCallFactory.class);

    @Inject
    public HookCallFactory() {
    }

    public HookCalls from(
            HandleContext handleContext,
            CryptoTransferTransactionBody userTxn,
            List<AssessedCustomFee> assessedCustomFees,
            final Map<TokenID, Map<AccountID, Long>> multiPayerNonNetFeeDeltas) {
        final var accountStore = handleContext.storeFactory().readableStore(ReadableAccountStore.class);
        final var memo = handleContext.body().memo();
        final var txnFee = handleContext.body().transactionFee();
        return getProposedTransfers(userTxn, accountStore, memo, txnFee, assessedCustomFees, multiPayerNonNetFeeDeltas);
    }

    private HookCalls getProposedTransfers(
            final CryptoTransferTransactionBody userTxn,
            final ReadableAccountStore accountStore,
            final String memo,
            final long txnFee,
            final List<AssessedCustomFee> assessedCustomFees,
            final Map<TokenID, Map<AccountID, Long>> multiPayerNonNetFeeDeltas) {
        final var preOnly = new ArrayList<HookInvocation>();
        final var prePost = new ArrayList<HookInvocation>();

        final var directTransfers = encodeTransfers(
                userTxn.transfersOrElse(TransferList.DEFAULT),
                userTxn.tokenTransfers(),
                accountStore,
                preOnly,
                prePost);

        final var customFeeTransfers = encodeCustomFees(accountStore, assessedCustomFees, multiPayerNonNetFeeDeltas);
        return new HookCalls(
                new HookContext(Tuple.of(directTransfers, customFeeTransfers), memo, txnFee), preOnly, prePost);
    }

    private Tuple encodeCustomFees(
            final ReadableAccountStore accountStore,
            final List<AssessedCustomFee> assessedCustomFees,
            final Map<TokenID, Map<AccountID, Long>> multiPayerNonNetFeeDeltas) {
        if (assessedCustomFees.isEmpty()) {
            return Tuple.of(new Tuple[]{}, new Tuple[]{});
        }
        final Map<TokenID, Map<AccountID, Long>> deltas = new HashMap<>();
        for (final var fee : assessedCustomFees) {
            final var token = fee.hasTokenId() ? fee.tokenIdOrThrow() : AssessmentResult.HBAR_TOKEN_ID;
            final var map = deltas.computeIfAbsent(token, t -> new LinkedHashMap<>());

            map.merge(fee.feeCollectorAccountIdOrThrow(), +fee.amount(), Long::sum);

            // if exactly one effective payer, debit it now; else skip, multi-payer handled below
            if (fee.effectivePayerAccountId().size() == 1) {
                final var payer = fee.effectivePayerAccountId().getFirst();
                map.merge(payer, -fee.amount(), Long::sum);
            }
        }
        // Merge in aggregated multi-payer non-net deltas
        if (multiPayerNonNetFeeDeltas != null) {
            for (final var e : multiPayerNonNetFeeDeltas.entrySet()) {
                final var map = deltas.computeIfAbsent(e.getKey(), t -> new LinkedHashMap<>());
                for (final var accDelta : e.getValue().entrySet()) {
                    map.merge(accDelta.getKey(), accDelta.getValue(), Long::sum);
                }
            }
        }
        // Construct TransferList + TokenTransferList[]
        final var hbarMap = deltas.getOrDefault(AssessmentResult.HBAR_TOKEN_ID, Map.of());
        final var hbarTransfers = TransferList.newBuilder()
                .accountAmounts(toAccountAmounts(hbarMap))
                .build();

        final var tokenTransfers = deltas.entrySet().stream()
                .filter(en -> !en.getKey().equals(AssessmentResult.HBAR_TOKEN_ID))
                .map(en -> TokenTransferList.newBuilder()
                        .token(en.getKey())
                        .transfers(toAccountAmounts(en.getValue()))
                        .build())
                .toList();

        log.info("XXXX Proposed customFees: HBAR {}, Tokens {}", hbarTransfers, tokenTransfers);

        // Reuse the normal encoder; used empty hook collector as custom fees don't introduce new hooks
        return encodeTransfers(hbarTransfers, tokenTransfers, accountStore, List.of(), List.of());
    }

    private static List<AccountAmount> toAccountAmounts(final Map<AccountID, Long> map) {
        final var out = new ArrayList<AccountAmount>(map.size());
        for (final var e : map.entrySet()) {
            final var amt = e.getValue();
            if (amt != 0L) {
                out.add(AccountAmount.newBuilder()
                        .accountID(e.getKey())
                        .amount(amt)
                        .build());
            }
        }
        return out;
    }

    /**
     * Transfers = (TransferList(AccountAmount[]), TokenTransferList[])
     * where AccountAmount tuple is (address,int64,bool) and NFT tuple is (address,address,int64,bool).
     * NOTE the hbar list is wrapped as a *tuple containing the array* to match your XFER_LIST_TUPLE "( (address,int64,bool)[] )"
     */
    private Tuple encodeTransfers(
            TransferList hbarTransfers,
            List<TokenTransferList> tokenTransfersList,
            ReadableAccountStore accountStore,
            List<HookInvocation> preOnly,
            List<HookInvocation> prePost) {
        final var hbarXfers = encodeAccountAmounts(hbarTransfers.accountAmounts(), accountStore, preOnly, prePost);
        final var tokenTransfers = tokenTransfersList.stream()
                .map(ttl -> encodeTokenTransfers(ttl, accountStore, preOnly, prePost))
                .toArray(Tuple[]::new);
        return Tuple.of(hbarXfers, tokenTransfers); // (TransferList(AccountAmount[]), TokenTransferList[])
    }

    @NonNull
    private Tuple encodeTokenTransfers(
            TokenTransferList ttl,
            ReadableAccountStore accounts,
            List<HookInvocation> preOnly,
            List<HookInvocation> prePost) {
        final var tokenAddress = headlongAddressOf(ttl.tokenOrThrow());
        final var transfers = encodeAccountAmounts(ttl.transfers(), accounts, preOnly, prePost);
        final var nftTransfers = encodeNftTransfers(ttl.nftTransfers(), accounts, preOnly, prePost);
        return Tuple.of(tokenAddress, transfers, nftTransfers); // (address, AccountAmount[], NftTransfer[])
    }

    private Tuple[] encodeNftTransfers(
            final List<NftTransfer> nftTransfers,
            final ReadableAccountStore accounts,
            List<HookInvocation> preOnly,
            List<HookInvocation> prePost) {
        return nftTransfers.stream()
                .map(nft -> {
                    final var sender = resolveAccountAddress(accounts, nft.senderAccountIDOrThrow());
                    final var receiver = resolveAccountAddress(accounts, nft.receiverAccountIDOrThrow());
                    if (nft.hasPreTxSenderAllowanceHook()) {
                        final var hook = nft.preTxSenderAllowanceHookOrThrow();
                        preOnly.add(new HookInvocation(
                                nft.senderAccountID(),
                                hook.hookIdOrThrow(),
                                sender,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    if (nft.hasPrePostTxSenderAllowanceHook()) {
                        final var hook = nft.prePostTxSenderAllowanceHookOrThrow();
                        prePost.add(new HookInvocation(
                                nft.senderAccountID(),
                                hook.hookIdOrThrow(),
                                sender,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    if (nft.hasPreTxReceiverAllowanceHook()) {
                        final var hook = nft.preTxReceiverAllowanceHookOrThrow();
                        preOnly.add(new HookInvocation(
                                nft.receiverAccountID(),
                                hook.hookIdOrThrow(),
                                receiver,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    if (nft.hasPrePostTxReceiverAllowanceHook()) {
                        final var hook = nft.prePostTxReceiverAllowanceHookOrThrow();
                        prePost.add(new HookInvocation(
                                nft.receiverAccountID(),
                                hook.hookIdOrThrow(),
                                receiver,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    final var serialNum = nft.serialNumber();
                    return Tuple.of(sender, receiver, serialNum); // (address,address,uint64)
                })
                .toArray(Tuple[]::new);
    }

    private Tuple[] encodeAccountAmounts(
            List<AccountAmount> items,
            ReadableAccountStore accountStore,
            List<HookInvocation> preOnly,
            List<HookInvocation> prePost) {
        return items.stream()
                .map(aa -> {
                    final var address = resolveAccountAddress(accountStore, aa.accountIDOrThrow());
                    final var amount = aa.amount();
                    if (aa.hasPreTxAllowanceHook()) {
                        final var hook = aa.preTxAllowanceHookOrThrow();
                        preOnly.add(new HookInvocation(
                                aa.accountID(),
                                hook.hookIdOrThrow(),
                                address,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    if (aa.hasPrePostTxAllowanceHook()) {
                        final var hook = aa.prePostTxAllowanceHookOrThrow();
                        prePost.add(new HookInvocation(
                                aa.accountID(),
                                hook.hookIdOrThrow(),
                                address,
                                hook.evmHookCallOrThrow().data(),
                                hook.evmHookCallOrThrow().gasLimit()));
                    }
                    return Tuple.of(address, amount); // (address,int64)
                })
                .toArray(Tuple[]::new);
    }

    /* ---------- Address helpers ---------- */
    private static Address resolveAccountAddress(final ReadableAccountStore accountStore, final AccountID ownerId) {
        final var owner = accountStore.getAccountById(ownerId);
        return priorityAddressOf(requireNonNull(owner));
    }

    /**
     * Given an account, returns its "priority" address as a Besu address.
     *
     * @param account the account
     * @return the priority address
     */
    private static Address priorityAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        return asHeadlongAddress(explicitAddressOf(account));
    }

    public static com.esaulpaugh.headlong.abi.Address asHeadlongAddress(@NonNull final byte[] explicit) {
        requireNonNull(explicit);
        final var integralAddress = org.apache.tuweni.bytes.Bytes.wrap(explicit).toUnsignedBigInteger();
        return com.esaulpaugh.headlong.abi.Address.wrap(toChecksumAddress(integralAddress));
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

    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId);
        return asHeadlongAddress(asEvmAddress(tokenId.tokenNum()));
    }

    public record HookInvocation(AccountID ownerId, long hookId, Address ownerAddress, Bytes calldata, long gasLimit) {
    }
}
