// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler.getHookInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.google.protobuf.ByteString;
import com.google.protobuf.UInt32Value;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoTransferMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;

public abstract class HapiBaseTransfer<T extends HapiTxnOp<T>> extends HapiTxnOp<T> {
    protected List<TokenMovement> tokenAwareProviders = Collections.emptyList();
    protected boolean fullyAggregateTokenTransfers = true;

    protected static final Comparator<AccountID> ACCOUNT_NUM_COMPARATOR = Comparator.comparingLong(
                    AccountID::getAccountNum)
            .thenComparingLong(AccountID::getShardNum)
            .thenComparingLong(AccountID::getRealmNum);
    protected static final Comparator<TokenID> TOKEN_ID_COMPARATOR = Comparator.comparingLong(TokenID::getTokenNum);
    protected static final Comparator<TokenTransferList> TOKEN_TRANSFER_LIST_COMPARATOR =
            (o1, o2) -> Objects.compare(o1.getToken(), o2.getToken(), TOKEN_ID_COMPARATOR);
    protected static final Comparator<AccountID> ACCOUNT_NUM_OR_ALIAS_COMPARATOR = (a, b) -> {
        if (!a.getAlias().isEmpty() || !b.getAlias().isEmpty()) {
            return ByteString.unsignedLexicographicalComparator().compare(a.getAlias(), b.getAlias());
        } else {
            return ACCOUNT_NUM_COMPARATOR.compare(a, b);
        }
    };
    protected static final Comparator<AccountAmount> ACCOUNT_AMOUNT_COMPARATOR = Comparator.comparingLong(
                    AccountAmount::getAmount)
            .thenComparing(AccountAmount::getAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR);

    protected static final Comparator<NftTransfer> NFT_TRANSFER_COMPARATOR = Comparator.comparing(
                    NftTransfer::getSenderAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR)
            .thenComparing(NftTransfer::getReceiverAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR)
            .thenComparingLong(NftTransfer::getSerialNumber);

    protected List<TokenTransferList> transfersAllFor(final HapiSpec spec) {
        return Stream.concat(transfersFor(spec).stream(), transfersForNft(spec).stream())
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }

    protected List<TokenTransferList> transfersFor(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> aggregated;
        if (fullyAggregateTokenTransfers) {
            aggregated = fullyAggregateTokenTransfersList(spec);
        } else {
            aggregated = aggregateOnTokenIds(spec);
        }

        return aggregated.entrySet().stream()
                .map(entry -> {
                    final var builder = TokenTransferList.newBuilder()
                            .setToken(entry.getKey())
                            .addAllTransfers(entry.getValue().getRight());
                    if (entry.getValue().getLeft() > 0) {
                        builder.setExpectedDecimals(
                                UInt32Value.of(entry.getValue().getLeft().intValue()));
                    }
                    return builder.build();
                })
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }

    protected Map<TokenID, Pair<Integer, List<AccountAmount>>> aggregateOnTokenIds(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> map = new HashMap<>();
        for (final TokenMovement tm : tokenAwareProviders) {
            if (tm.isFungibleToken()) {
                final var list = tm.specializedFor(spec);

                if (map.containsKey(list.getToken())) {
                    final var existingVal = map.get(list.getToken());
                    final List<AccountAmount> newList = Stream.of(existingVal.getRight(), list.getTransfersList())
                            .flatMap(Collection::stream)
                            .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                            .toList();

                    map.put(list.getToken(), Pair.of(existingVal.getLeft(), newList));
                } else {
                    map.put(list.getToken(), Pair.of(list.getExpectedDecimals().getValue(), list.getTransfersList()));
                }
            }
        }
        return map;
    }

    protected Map<TokenID, Pair<Integer, List<AccountAmount>>> fullyAggregateTokenTransfersList(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> map = new HashMap<>();
        for (final TokenMovement xfer : tokenAwareProviders) {
            if (xfer.isFungibleToken()) {
                final var list = xfer.specializedFor(spec);

                if (map.containsKey(list.getToken())) {
                    final var existingVal = map.get(list.getToken());
                    final List<AccountAmount> newList = Stream.of(existingVal.getRight(), list.getTransfersList())
                            .flatMap(Collection::stream)
                            .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                            .toList();

                    map.put(list.getToken(), Pair.of(existingVal.getLeft(), aggregateTransfers(newList)));
                } else {
                    map.put(
                            list.getToken(),
                            Pair.of(
                                    list.getExpectedDecimals().getValue(),
                                    aggregateTransfers(list.getTransfersList())));
                }
            }
        }
        return map;
    }

    protected List<AccountAmount> aggregateTransfers(final List<AccountAmount> list) {
        return list.stream()
                .collect(groupingBy(
                        AccountAmount::getAccountID,
                        groupingBy(AccountAmount::getIsApproval, mapping(AccountAmount::getAmount, toList()))))
                .entrySet()
                .stream()
                .flatMap(entry -> {
                    final List<AccountAmount> accountAmounts = new ArrayList<>();
                    for (final var entrySet : entry.getValue().entrySet()) {
                        final var aa = AccountAmount.newBuilder()
                                .setAccountID(entry.getKey())
                                .setIsApproval(entrySet.getKey())
                                .setAmount(entrySet.getValue().stream()
                                        .mapToLong(l -> l)
                                        .sum())
                                .build();
                        accountAmounts.add(aa);
                    }
                    return accountAmounts.stream();
                })
                .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                .toList();
    }

    protected List<TokenTransferList> transfersForNft(final HapiSpec spec) {
        final var uniqueCount = tokenAwareProviders.stream()
                .filter(Predicate.not(TokenMovement::isFungibleToken))
                .map(TokenMovement::getToken)
                .distinct()
                .count();
        final Map<TokenID, List<NftTransfer>> aggregated = tokenAwareProviders.stream()
                .filter(Predicate.not(TokenMovement::isFungibleToken))
                .map(p -> p.specializedForNft(spec))
                .collect(Collectors.toMap(
                        TokenTransferList::getToken,
                        TokenTransferList::getNftTransfersList,
                        (left, right) -> Stream.of(left, right)
                                .flatMap(Collection::stream)
                                .sorted(NFT_TRANSFER_COMPARATOR)
                                .toList(),
                        LinkedHashMap::new));
        if (aggregated.size() != 0 && uniqueCount != aggregated.size()) {
            throw new RuntimeException("Aggregation seems to have failed (expected "
                    + uniqueCount
                    + " distinct unique token types, got "
                    + aggregated.size()
                    + ")");
        }
        return aggregated.entrySet().stream()
                .map(entry -> TokenTransferList.newBuilder()
                        .setToken(entry.getKey())
                        .addAllNftTransfers(entry.getValue())
                        .build())
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }

    public static FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo, final int multiplier) {
        final var op = txn.getCryptoTransfer();

        final var baseMeta = new BaseTransactionMeta(
                txn.getMemoBytes().size(), op.getTransfers().getAccountAmountsCount());

        int numTokensInvolved = 0, numTokenTransfers = 0, numNftOwnershipChanges = 0;
        for (final var tokenTransfers : op.getTokenTransfersList()) {
            numTokensInvolved++;
            numTokenTransfers += tokenTransfers.getTransfersCount();
            numNftOwnershipChanges += tokenTransfers.getNftTransfersCount();
        }

        final CryptoTransferHandler.HookInfo hookInfo;
        if (txn.hasCryptoTransfer()) {
            hookInfo = getHookInfo(toPbj(txn).cryptoTransferOrThrow());
        } else {
            hookInfo = CryptoTransferHandler.HookInfo.NO_HOOKS;
        }

        final var xferUsageMeta = new CryptoTransferMeta(
                multiplier, numTokensInvolved, numTokenTransfers, numNftOwnershipChanges, hookInfo.usesHooks());

        final var accumulator = new UsageAccumulator();
        cryptoOpsUsage.cryptoTransferUsage(
                suFrom(svo),
                xferUsageMeta,
                baseMeta,
                accumulator,
                hookInfo.totalGasLimitOfHooks(),
                hookInfo.usesHooks());

        final var feeData = AdapterUtils.feeDataFrom(accumulator);
        return feeData.toBuilder().setSubType(xferUsageMeta.getSubType()).build();
    }
}
