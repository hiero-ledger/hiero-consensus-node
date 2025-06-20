// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.services.bdd.junit.TestBase.concurrentExecutionOf;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.node.app.hapi.utils.forensics.TransactionParts;
import com.hedera.services.bdd.junit.TestBase;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.validators.utils.AccountClassifier;
import com.hedera.services.bdd.suites.records.TokenBalanceValidation;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This validator "reconciles" (compares) the HTS token balances of all accounts between the record
 * stream and the network state, comparing two sources of truth at the end of the CI test run:
 *
 * <ol>
 *   <li>The balances implied by the {@code TransferList} adjustments in the record stream.
 *   <li>The balances returned by {@code hasTokenBalance} queries.
 *   NOTE: Since tokenBalances are no more returned from the query, the validation should be modified
 * </ol>
 *
 * <p>It uses the {@link com.hedera.services.bdd.suites.records.TokenBalanceValidation} suite to perform the queries.
 */
public class TokenReconciliationValidator implements RecordStreamValidator {
    private final Map<AccountNumTokenNum, Long> expectedTokenBalances = new HashMap<>();

    private final AccountClassifier accountClassifier = new AccountClassifier();
    private final Map<TokenID, AccountID> nonFungibleTreasuries = new HashMap<>();

    @Override
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordsWithSidecars) {
        getExpectedBalanceFrom(recordsWithSidecars);

        final var validationSpecs = TestBase.extractContextualizedSpecsFrom(
                List.of(() -> new TokenBalanceValidation(expectedTokenBalances, accountClassifier)),
                TestBase::contextualizedSpecsFromConcurrent);
        concurrentExecutionOf(validationSpecs);
    }

    private void getExpectedBalanceFrom(final List<RecordWithSidecars> recordsWithSidecars) {
        for (final var recordWithSidecars : recordsWithSidecars) {
            final var items = recordWithSidecars.recordFile().getRecordStreamItemsList();
            for (final var item : items) {
                accountClassifier.incorporate(item);
                final var grpcRecord = item.getRecord();
                final var receipt = grpcRecord.getReceipt();
                if (receipt.hasTokenID()) {
                    maybeIncorporateNonFungibleTreasury(item);
                } else if (receipt.getStatus() == SUCCESS) {
                    updateImplicitNftBalanceChangesIfTokenUpdate(item);
                }
                grpcRecord.getTokenTransferListsList().forEach(tokenTransferList -> {
                    final var tokenNum = tokenTransferList.getToken().getTokenNum();
                    tokenTransferList.getTransfersList().forEach(tokenTransfers -> {
                        final long accountNum = tokenTransfers.getAccountID().getAccountNum();
                        final long amount = tokenTransfers.getAmount();
                        expectedTokenBalances.merge(new AccountNumTokenNum(accountNum, tokenNum), amount, Long::sum);
                    });
                    tokenTransferList.getNftTransfersList().forEach(nftTransfer -> {
                        final var serialNo = nftTransfer.getSerialNumber();
                        // -1 is a sentinel value representing a treasury change
                        if (serialNo != -1) {
                            final var senderNum =
                                    nftTransfer.getSenderAccountID().getAccountNum();
                            if (senderNum > 0) {
                                expectedTokenBalances.merge(
                                        new AccountNumTokenNum(senderNum, tokenNum), -1L, Long::sum);
                            }
                            final var receiverNum =
                                    nftTransfer.getReceiverAccountID().getAccountNum();
                            if (receiverNum > 0) {
                                expectedTokenBalances.merge(
                                        new AccountNumTokenNum(receiverNum, tokenNum), 1L, Long::sum);
                            }
                        }
                    });
                });
            }
        }
    }

    private void updateImplicitNftBalanceChangesIfTokenUpdate(@NonNull final RecordStreamItem item) {
        final var parts = TransactionParts.from(item.getTransaction());
        if (parts.function() == TokenUpdate) {
            final var op = parts.body().getTokenUpdate();
            final var nftTreasury = nonFungibleTreasuries.get(op.getToken());
            // A synthetic TokenUpdate dispatched by a system contract will set 0.0.0 when not changing the treasury
            if (nftTreasury != null && op.hasTreasury() && op.getTreasury().getAccountNum() > 0) {
                final var tokenNum = op.getToken().getTokenNum();
                final var treasuryNum = nftTreasury.getAccountNum();
                final var curTreasuryKey = new AccountNumTokenNum(treasuryNum, tokenNum);
                final var numTreasuryNfts = expectedTokenBalances.get(curTreasuryKey);
                assertNotNull(
                        numTreasuryNfts,
                        "Treasury-owned NFT count for non-fungible token 0.0." + tokenNum
                                + " not found for treasury 0.0." + treasuryNum);
                expectedTokenBalances.put(new AccountNumTokenNum(treasuryNum, tokenNum), 0L);
                final var newTreasuryNum = op.getTreasury().getAccountNum();
                expectedTokenBalances.put(new AccountNumTokenNum(newTreasuryNum, tokenNum), numTreasuryNfts);
            }
        }
    }

    private void maybeIncorporateNonFungibleTreasury(@NonNull final RecordStreamItem creation) {
        final var parts = TransactionParts.from(creation.getTransaction());
        final var op = parts.body().getTokenCreation();
        if (op.getTokenType() == TokenType.NON_FUNGIBLE_UNIQUE) {
            final var tokenId = creation.getRecord().getReceipt().getTokenID();
            nonFungibleTreasuries.put(tokenId, op.getTreasury());
            expectedTokenBalances.put(
                    new AccountNumTokenNum(op.getTreasury().getAccountNum(), tokenId.getTokenNum()), 0L);
        }
    }
}
