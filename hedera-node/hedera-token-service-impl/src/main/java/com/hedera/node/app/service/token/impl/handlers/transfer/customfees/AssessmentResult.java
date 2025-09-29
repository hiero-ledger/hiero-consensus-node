// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Contains the adjustments of accounts and tokens as a result of a token transfer. This result is passed through all
 * the steps of CryptoTransfer.
 * <p> The adjustment maps are divided into two categories: mutable and immutable (for both hbar and tokens).
 * The mutable maps are used to accumulate custom fee assessed changes during the assessment process. The immutable
 * maps are used to store the original changes that were passed to the assessment process from the input transaction
 * body. </p>
 * <p> The immutableInputTokenAdjustments and immutableInputHbarAdjustments are used to store the original changes that
 * were passed to the assessment process from the input transaction body. </p>
 * <p> The mutableInputBalanceAdjustments is created from immutableInputHbarAdjustments and is used to accumulate
 * custom fee assessed balance changes during the assessment process. </p>
 * <p> The htsAdjustments map is created from immutableInputTokenAdjustments and used to store the token balance
 * changes that are assessed custom fees. </p>
 * <p> The mutable maps are passed to next level by constructing a valid transaction body from the changes accumulated.
 * Then the transaction body is passed to next level to assess custom fees. </p>
 * <p> All the assessed custom fees are stored in assessedCustomFees, which is used to construct transaction record</p>
 */
public class AssessmentResult {
    /**
     * The default token ID for representing hbar changes.
     */
    public static final TokenID HBAR_TOKEN_ID = TokenID.DEFAULT;

    private final Map<TokenID, Map<AccountID, Long>> htsAdjustments;
    // two maps to aggregate all custom fee balance changes. These two maps are used
    // to construct a transaction body that needs to be assessed again for custom fees
    private final Map<AccountID, Long> hbarAdjustments;
    // In a given CryptoTransfer, we only charge royalties to an account once per token type; so
    // even if 0.0.A is sending multiple NFTs of type 0.0.T in a single transfer, we only deduct
    // royalty fees once from the value it receives in return. This is used to track the royalties
    // that have been paid in a given CryptoTransfer.
    private final Set<Pair<AccountID, TokenID>> royaltiesPaid;
    // Contains token adjustments from the transaction body.
    private final Map<TokenID, Map<AccountID, Long>> immutableInputTokenAdjustments;
    // Contains Hbar and token adjustments. Hbar adjustments are used using a sentinel tokenId key
    private final Map<TokenID, Map<AccountID, Long>> mutableInputBalanceAdjustments;
    private final Map<AccountID, Long> immutableInputHbarAdjustments;
    /* And for each "assessable change" that can be charged a custom fee, delegate to our
    fee assessor to update the balance changes with the custom fee. */
    private final List<AssessedFeeWithPayerDebits> assessedFeesWithPayerDebits;

    /**
     * Constructs an AssessmentResult object with the input token transfers and hbar transfers
     * from the transaction body.
     * @param tokenTransfers the token transfers
     * @param hbarTransfers the hbar transfers
     */
    public AssessmentResult(final List<TokenTransferList> tokenTransfers, final List<AccountAmount> hbarTransfers) {
        mutableInputBalanceAdjustments = buildFungibleTokenTransferMap(tokenTransfers);
        immutableInputTokenAdjustments = Collections.unmodifiableMap(mutableInputBalanceAdjustments.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new)));

        immutableInputHbarAdjustments = buildHbarTransferMap(hbarTransfers);
        mutableInputBalanceAdjustments.put(HBAR_TOKEN_ID, new LinkedHashMap<>(immutableInputHbarAdjustments));

        htsAdjustments = new LinkedHashMap<>();
        hbarAdjustments = new LinkedHashMap<>();
        royaltiesPaid = new LinkedHashSet<>();
        assessedFeesWithPayerDebits = new ArrayList<>();
    }

    /**
     * Returns the immutable input token adjustments.
     * @return the immutable input token adjustments
     */
    public Map<TokenID, Map<AccountID, Long>> getImmutableInputTokenAdjustments() {
        return immutableInputTokenAdjustments;
    }

    /**
     * Returns the mutable input balance adjustments.
     * @return the mutable input balance adjustments
     */
    public Map<TokenID, Map<AccountID, Long>> getMutableInputBalanceAdjustments() {
        return mutableInputBalanceAdjustments;
    }

    /**
     * Returns the hbar adjustments.
     * @return the hbar adjustments
     */
    public Map<AccountID, Long> getHbarAdjustments() {
        return hbarAdjustments;
    }

    /**
     * Returns the hts adjustments.
     * @return the hts adjustments
     */
    public Map<TokenID, Map<AccountID, Long>> getHtsAdjustments() {
        return htsAdjustments;
    }

    /**
     * Returns the assessed custom fees.
     * @return the assessed custom fees
     */
    public List<AssessedFeeWithPayerDebits> getAssessedFeesWithPayerDebits() {
        return assessedFeesWithPayerDebits;
    }

    /**
     * Adds an assessed custom fee.
     *
     * @param assessedCustomFee the assessed custom fee
     * @param multiPayerDeltas the adjustments to the assessed fee's payer in a multi-payer fractional fee scenario
     */
    public void addAssessedFeeWithPayerDebits(
            @NonNull final AssessedCustomFee assessedCustomFee, @NonNull final Map<AccountID, Long> multiPayerDeltas) {
        assessedFeesWithPayerDebits.add(new AssessedFeeWithPayerDebits(assessedCustomFee, multiPayerDeltas));
    }

    /**
     * Adds an assessed custom fee. Payer debits will be null, since it is applicable only for fractional fees.
     *
     * @param assessedCustomFee the assessed custom fee
     */
    public void addAssessedCustomFee(@NonNull final AssessedCustomFee assessedCustomFee) {
        assessedFeesWithPayerDebits.add(new AssessedFeeWithPayerDebits(assessedCustomFee, null));
    }

    /**
     * Returns the royalties paid.
     * @return the royalties paid
     */
    public Set<Pair<AccountID, TokenID>> getRoyaltiesPaid() {
        return royaltiesPaid;
    }

    /**
     * Adds a pair of account ID and token ID to the royalties paid.
     * @param paid the pair of account ID and token ID
     */
    public void addToRoyaltiesPaid(final Pair<AccountID, TokenID> paid) {
        royaltiesPaid.add(paid);
    }

    /**
     * Returns the immutable input hbar adjustments.
     * @return the immutable input hbar adjustments
     */
    public Map<AccountID, Long> getImmutableInputHbarAdjustments() {
        return immutableInputHbarAdjustments;
    }

    /**
     * Builds a map of fungible token transfers from the given {@link TokenTransferList}.
     * @param tokenTransfers the token transfers
     * @return the map of fungible token transfers
     */
    private Map<TokenID, Map<AccountID, Long>> buildFungibleTokenTransferMap(
            final List<TokenTransferList> tokenTransfers) {
        final var fungibleTransfersMap = new LinkedHashMap<TokenID, Map<AccountID, Long>>();
        for (final var xfer : tokenTransfers) {
            final var tokenId = xfer.token();
            final var fungibleTokenTransfers = xfer.transfers();
            if (fungibleTokenTransfers.isEmpty()) {
                continue;
            }
            final Map<AccountID, Long> tokenTransferMap = new LinkedHashMap<>();
            for (final var aa : fungibleTokenTransfers) {
                tokenTransferMap.put(aa.accountID(), aa.amount());
            }
            fungibleTransfersMap.put(tokenId, tokenTransferMap);
        }
        return fungibleTransfersMap;
    }

    /**
     * Builds a map of hbar transfers from the given {@link AccountAmount}.
     * @param hbarTransfers the hbar transfers
     * @return the map of hbar transfers
     */
    private Map<AccountID, Long> buildHbarTransferMap(@NonNull final List<AccountAmount> hbarTransfers) {
        final var adjustments = new LinkedHashMap<AccountID, Long>();
        for (final var aa : hbarTransfers) {
            adjustments.put(aa.accountID(), aa.amount());
        }
        return adjustments;
    }
}
