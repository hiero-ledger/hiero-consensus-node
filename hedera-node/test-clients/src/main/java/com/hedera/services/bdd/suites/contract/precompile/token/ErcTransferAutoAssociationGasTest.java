// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertCloseEnough;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Asserts the ERC-20 and ERC-721 transfer redirects charge for the auto-associations they create.
 *
 * <p>The token service does not charge auto-association fees inline for internal dispatches, on the explicit
 * assumption that the contract service takes those costs from the remaining EVM gas; so a transfer through an
 * ERC redirect must cost roughly one association fee more when the receiver has to be auto-associated. This is
 * the ERC analogue of {@code UnlimitedAutoAssociationSuite#autoAssociationThroughSystemContractChangesGasCost},
 * which asserts the same thing for the "classic" HTS transfer calls.
 */
@Tag(SMART_CONTRACT)
@DisplayName("ERC transfer auto-association gas")
public class ErcTransferAutoAssociationGasTest {
    // Note we have a 20% markup on doing HAPI operations through the EVM
    private static final double EXPECTED_USD_ASSOCIATION_FEE = 0.05 * 1.2;
    // Enough to cover the transfer plus an association, which alone is priced at ~705K gas
    private static final long CALL_GAS = 2_000_000L;
    private static final String NO_AUTO_ASSOCIATION_TXN = "noAutoAssociation";
    private static final String AUTO_ASSOCIATION_TXN = "autoAssociation";

    @Contract(contract = "ERC20Contract", creationGas = 4_000_000L)
    static SpecContract erc20Contract;

    @Contract(contract = "ERC721Contract", creationGas = 4_000_000L)
    static SpecContract erc721Contract;

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    @DisplayName("ERC-20 transfer charges for an auto-association")
    final Stream<DynamicTest> erc20TransferChargesForAutoAssociation(
            @FungibleToken(initialSupply = 1_000) SpecFungibleToken token,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) SpecAccount preAssociated,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS, maxAutoAssociations = 1) SpecAccount autoAssociated) {
        return hapiTest(
                refundAllUnusedGas(),
                preAssociated.associateTokens(token),
                erc20Contract.associateTokens(token),
                token.treasury().transferUnitsTo(erc20Contract, 100L, token),
                // Make two calls, the first with no auto-association and the second with auto-association
                erc20Contract
                        .call("transfer", token, preAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(NO_AUTO_ASSOCIATION_TXN),
                erc20Contract
                        .call("transfer", token, autoAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(AUTO_ASSOCIATION_TXN),
                assertGasDifferenceIsAnAssociationFee());
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    @DisplayName("ERC-20 transferFrom charges for an auto-association")
    final Stream<DynamicTest> erc20TransferFromChargesForAutoAssociation(
            @FungibleToken(initialSupply = 1_000) SpecFungibleToken token,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) SpecAccount owner,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) SpecAccount preAssociated,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS, maxAutoAssociations = 1) SpecAccount autoAssociated) {
        return hapiTest(
                refundAllUnusedGas(),
                preAssociated.associateTokens(token),
                owner.associateTokens(token),
                token.treasury().transferUnitsTo(owner, 100L, token),
                // Unlike ERC-721, the ERC-20 transferFrom redirect always dispatches with approvals, so the
                // calling contract needs an explicit allowance even though it never owns the units
                owner.approveTokenAllowance(token, erc20Contract, 100L),
                erc20Contract
                        .call("transferFrom", token, owner, preAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(NO_AUTO_ASSOCIATION_TXN),
                erc20Contract
                        .call("transferFrom", token, owner, autoAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(AUTO_ASSOCIATION_TXN),
                assertGasDifferenceIsAnAssociationFee());
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    @DisplayName("ERC-721 transferFrom charges for an auto-association")
    final Stream<DynamicTest> erc721TransferFromChargesForAutoAssociation(
            @NonFungibleToken(numPreMints = 2) SpecNonFungibleToken token,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) SpecAccount preAssociated,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS, maxAutoAssociations = 1) SpecAccount autoAssociated) {
        return hapiTest(
                refundAllUnusedGas(),
                preAssociated.associateTokens(token),
                erc721Contract.associateTokens(token),
                // The contract is the owner of both serials, so no allowance is needed; the redirect only sets
                // isApproval when the sender differs from the owner
                token.treasury().transferNFTsTo(erc721Contract, token, 1L, 2L),
                erc721Contract
                        .call("transferFrom", token, erc721Contract, preAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(NO_AUTO_ASSOCIATION_TXN),
                erc721Contract
                        .call("transferFrom", token, erc721Contract, autoAssociated, BigInteger.TWO)
                        .gas(CALL_GAS)
                        .via(AUTO_ASSOCIATION_TXN),
                assertGasDifferenceIsAnAssociationFee());
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit", "entities.unlimitedAutoAssociationsEnabled"})
    @DisplayName("ERC-20 transfer does not charge for an auto-association if the sender-pays model is off")
    final Stream<DynamicTest> erc20TransferChargesNothingWhenUnlimitedAssociationsDisabled(
            @FungibleToken(initialSupply = 1_000) SpecFungibleToken token,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) SpecAccount preAssociated,
            @Account(tinybarBalance = ONE_HUNDRED_HBARS, maxAutoAssociations = 1) SpecAccount autoAssociated) {
        return hapiTest(
                refundAllUnusedGas(),
                overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                preAssociated.associateTokens(token),
                erc20Contract.associateTokens(token),
                token.treasury().transferUnitsTo(erc20Contract, 100L, token),
                erc20Contract
                        .call("transfer", token, preAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(NO_AUTO_ASSOCIATION_TXN),
                erc20Contract
                        .call("transfer", token, autoAssociated, BigInteger.ONE)
                        .gas(CALL_GAS)
                        .via(AUTO_ASSOCIATION_TXN),
                // The auto-association still happens, only the "sender pays" fee model is disabled
                exposingUsdGasDifferenceTo(usdDiff -> assertTrue(
                        Math.abs(usdDiff) < EXPECTED_USD_ASSOCIATION_FEE / 10,
                        "Auto-associating cost " + usdDiff + " USD of extra gas, but the sender-pays fee model"
                                + " is disabled so it should have cost nothing")));
    }

    /**
     * Refunds all unused gas so the gas actually consumed by the two calls is directly comparable; without this
     * the reported gas used is floored at {@code gasLimit - gasLimit * maxRefundPercentOfGasLimit / 100} and both
     * calls report the same figure.
     */
    private static SpecOperation refundAllUnusedGas() {
        return overriding("contracts.maxRefundPercentOfGasLimit", "100");
    }

    private static SpecOperation assertGasDifferenceIsAnAssociationFee() {
        return exposingUsdGasDifferenceTo(usdDiff -> assertCloseEnough(
                EXPECTED_USD_ASSOCIATION_FEE,
                usdDiff,
                // Allow at most one percent deviation from expected
                1.0,
                "USD value of gas difference",
                "auto-association fee"));
    }

    /**
     * Exposes the USD value of the difference in gas used by the {@code autoAssociation} and
     * {@code noAutoAssociation} transactions to the given assertion.
     */
    private static SpecOperation exposingUsdGasDifferenceTo(@NonNull final DoubleConsumer assertion) {
        final var gasWithoutAutoAssociation = new AtomicLong();
        final var gasWithAutoAssociation = new AtomicLong();
        return withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    getTxnRecord(NO_AUTO_ASSOCIATION_TXN)
                            .exposingTo(txnRecord -> gasWithoutAutoAssociation.set(
                                    txnRecord.getContractCallResult().getGasUsed())),
                    getTxnRecord(AUTO_ASSOCIATION_TXN)
                            .exposingTo(txnRecord -> gasWithAutoAssociation.set(
                                    txnRecord.getContractCallResult().getGasUsed())));
            final var gasDiff = gasWithAutoAssociation.get() - gasWithoutAutoAssociation.get();
            // Convert to USD by multiplying the gas difference by the price in thousandths of a tinycent
            // from the fee schedule; and then dividing by 1e13 to convert to USD
            final var approxUsdDiff = (1.0
                            * gasDiff
                            * spec.ratesProvider().gasPriceInThousandthsOfTinycent()
                            / 1000
                            / TINY_PARTS_PER_WHOLE)
                    / 100.0;
            assertion.accept(approxUsdDiff);
        });
    }
}
