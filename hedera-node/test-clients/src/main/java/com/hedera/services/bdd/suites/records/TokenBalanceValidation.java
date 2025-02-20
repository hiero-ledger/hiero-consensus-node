// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.support.validators.AccountNumTokenNum;
import com.hedera.services.bdd.junit.support.validators.utils.AccountClassifier;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests to validate that token balances are correct after token transfers occur.
 */
public class TokenBalanceValidation extends HapiSuite {
    private static final Logger log = LogManager.getLogger(TokenBalanceValidation.class);
    private final Map<AccountNumTokenNum, Long> expectedTokenBalances;
    private final AccountClassifier accountClassifier;
    private final boolean createTransferTransactions;

    /**
     * Set up validator. Private constructor for use from main(_).
     * If <code>createTransferTransactions</code> is true, this constructor sets up HAPI transactions
     * to create and transfer tokens to the given account.
     *
     * @param expectedTokenBalances Map of (accountNum, tokenNum) -> token balance.
     * @param accountClassifier whether the accounts are contracts
     * @param createTransferTransactions If true, create and transfer the tokens as part of setup.
     *                          If false, assume that the tokens have already been created.
     */
    private TokenBalanceValidation(
            final Map<AccountNumTokenNum, Long> expectedTokenBalances,
            final AccountClassifier accountClassifier,
            final boolean createTransferTransactions) {
        this.expectedTokenBalances = expectedTokenBalances;
        this.accountClassifier = accountClassifier;
        this.createTransferTransactions = createTransferTransactions;
    }

    /**
     * Set up validator. Public constructor for use from <code>TokenReconciliationValidator</code>>
     * Assumes that the tokens have already been created, and validation is only to check that
     * <code>expectedTokenBalances</code> match values returned by <code>hasTokenBalance</code>
     * NOTE: Since token balances are no more returned from query, this validator's validation should be changed
     *
     * @param expectedTokenBalances Map of (accountNum, tokenNum) -> token balance.
     * @param accountClassifier whether the accounts are contracts
     */
    public TokenBalanceValidation(
            final Map<AccountNumTokenNum, Long> expectedTokenBalances, final AccountClassifier accountClassifier) {
        this(expectedTokenBalances, accountClassifier, false);
    }

    /**
     * Create test data and run validator.
     * @param args ignored
     */
    public static void main(String... args) {
        // define expected amount for a simple token create
        final Long aFungibleToken = 12L;
        final Long aFungibleAmount = 1_000L;
        final Long aReceiverAccount = 3L;

        // define expected amount for a token create + token transfer
        final Long bFungibleToken = 10L;
        final Long bFungibleAmount = 321L;
        final Long bReceiverAccount = 4L;

        // set up a map of expected token balances
        Map<AccountNumTokenNum, Long> expectedTokenBalances = Map.of(
                new AccountNumTokenNum(aReceiverAccount, aFungibleToken),
                aFungibleAmount,
                new AccountNumTokenNum(bReceiverAccount, bFungibleToken),
                bFungibleAmount);

        // run validation using the expected balances
        new TokenBalanceValidation(expectedTokenBalances, new AccountClassifier(), true).runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(validateTokenBalances());
    }

    /**
     * Set up for creating and transferring token balances.
     * @return array of operations needed to create tokens and transfer them to the accounts as specified in
     * <code>expectedTokenBalances</code>
     */
    private HapiSpecOperation[] getHapiSpecsForTransferTxs() {
        // if transactions have already been created, there's nothing to do so return an empty array
        if (!createTransferTransactions) return new HapiSpecOperation[0];

        // otherwise return an array of operations needed to create and transfer the tokens
        // specified in <code>expectedTokenBalances</code>
        return expectedTokenBalances.entrySet().stream()
                .map(entry -> {
                    final var accountNum = entry.getKey().accountNum();
                    final var tokenNum = entry.getKey().tokenNum();
                    final var tokenAmt = entry.getValue();
                    return new HapiSpecOperation[] {
                        // set up HAPI operations to create and transfer a token
                        // in a later method we'll validate that the receiver has the correct token balance

                        // create treasury account
                        cryptoCreate(TOKEN_TREASURY).balance(10000 * ONE_HUNDRED_HBARS),
                        // create receiver account
                        cryptoCreate("0.0." + accountNum).balance(100 * ONE_HUNDRED_HBARS),
                        // create token
                        tokenCreate(tokenNum.toString())
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(tokenAmt * 10)
                                .name(tokenNum.toString()),
                        tokenAssociate("0.0." + accountNum, List.of(tokenNum.toString())),
                        // transfer the token from the treasury to the account
                        cryptoTransfer(
                                moving(tokenAmt, tokenNum.toString()).between(TOKEN_TREASURY, "0.0." + accountNum)),
                    };
                })
                .flatMap(Arrays::stream)
                .toArray(HapiSpecOperation[]::new);
    }

    /**
     * Create HAPI queries to check whether token balances match what's given in <code>expectedTokenBalances</code>
     * @return HAPI queries to execute
     */
    final Stream<DynamicTest> validateTokenBalances() {
        return hapiTest(flattened(
                getHapiSpecsForTransferTxs(), // set up transfers if needed
                inParallel(expectedTokenBalances.entrySet().stream()
                                .map(
                                        entry -> { // for each expectedTokenBalance
                                            final var accountNum =
                                                    entry.getKey().accountNum();
                                            final var tokenNum = entry.getKey().tokenNum();
                                            final var tokenAmt = entry.getValue();

                                            // validate that the transfer worked and the receiver account
                                            // has the tokens
                                            return QueryVerbs.getAccountBalance(
                                                            "0.0." + accountNum,
                                                            accountClassifier.isContract(accountNum))
                                                    .hasAnswerOnlyPrecheckFrom(
                                                            OK,
                                                            CONTRACT_DELETED,
                                                            ACCOUNT_DELETED,
                                                            INVALID_CONTRACT_ID,
                                                            INVALID_ACCOUNT_ID)
                                                    .hasTokenBalance("0.0." + tokenNum, tokenAmt)
                                                    .includeTokenMemoOnError();
                                        })
                                .toArray(HapiSpecOperation[]::new))
                        .failOnErrors()));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
