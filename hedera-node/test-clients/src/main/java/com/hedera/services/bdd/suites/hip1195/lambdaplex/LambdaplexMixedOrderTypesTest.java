// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195.lambdaplex;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingHbarCredit;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.FillParty.*;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.HBAR;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.assertFirstError;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.assertSecondError;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.averagePrice;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.distantExpiry;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.inBaseUnits;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.iocExpiry;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.notional;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.price;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.quantity;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.randomB64Salt;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Hook;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests exercising the Lambdaplex protocol hook, with an emphasis on mixed LIMIT and MARKET orders. (Has some intentional mild
 * duplication with other test classes in this package to keep each test class more self-contained.)
 * <p>
 * The entries in mapping zero of this hook represent orders (limit, market, stop limit, or stop market) on a
 * self-custodied spot exchange. Each mapping's key and value are packed bytes with a specific layout.
 * <p>
 * The mapping key's bytes include,
 * <ul>
 *   <li>The <b>order type</b> (limit, market, stop limit, or stop market); and for a stop, the <b>direction</b>.</li>
 *   <li>The Hedera token entity number of the <b>output token</b> the hook owner is willing to be debited.</li>
 *   <li>The Hedera token entity number of the <b>input token</b> the hook owner wants to be credited.</li>
 *   <li>The maximum fee, in <b>centi-bps (hundredths of a basis point)</b>, the hook owner is willing to pay. (This
 *   fee is always deducted from the input token amount.)</li>
 *   <li>The consensus expiration second at which the order expires.</li>
 *   <li>A 7-byte <b>salt</b> that is globally unique across all the user's orders; might be a counter or random.</li>
 * </ul>
 * And the mapping value's bytes include,
 * <ul>
 *   <li>The order size as a <b>maximum debit</b> in base units of the output token.</li>
 *   <li>The order's <b>price</b>, in base units of the input token per base unit of the output token. For a limit or
 *   stop limit order, this is exact price at which the order must fill. For a market or stop market order, this is the
 *   reference price to compute slippage tolerance against. (So it is also the trigger price for a stop market order.)
 *   </li>
 *   <li>The order's <b>maximum deviation</b> from its price, in <b>centi-bps</b>. This has no
 *   significance for a limit order, but for a stop limit order implies the trigger price. For a market or stop
 *   market order, this is the slippage tolerance.</li>
 *   <li>The order's <b>minimum fill</b> fraction in centi-bps; hence if set to one million, the order has onchain
 *   fill-or-kill semantics.</li>
 * </ul>
 * Stop orders are triggered by providing an oracle proof of a (sufficiently recent) price that hits the trigger.
 * <p>
 * Annotated as {@link OrderedInIsolation} because the mock Supra pull oracle is stateful (keeps the next expected
 * {@code PriceInfo} in storage).
 */
@TargetEmbeddedMode(REPEATABLE)
@HapiTestLifecycle
@OrderedInIsolation
public class LambdaplexMixedOrderTypesTest implements InitcodeTransform {
    private static final int HOOK_ID = 42;
    private static final int ZERO_BPS = 0;
    private static final int MAKER_BPS = 12;
    private static final int TAKER_BPS = 25;
    private static final Fees FEES = new Fees(MAKER_BPS, TAKER_BPS);

    private static final String REGISTRY_ADDRESS_TPL = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";

    private static final int APPLES_DECIMALS = 4;
    private static final int BANANAS_DECIMALS = 5;
    private static final int USDC_DECIMALS = 6;
    private static final long APPLES_SCALE = 10L * 10L * 10L * 10L;
    private static final long BANANAS_SCALE = 10L * 10L * 10L * 10L * 10L;
    private static final long USDC_SCALE = 10L * 10L * 10L * 10L * 10L * 10L;
    private static final Bytes MOCK_ORACLE_PROOF = Bytes.wrap(new byte[] {(byte) 0xa1, (byte) 0xb2, (byte) 0xc3});

    @Contract(contract = "MockSupraRegistry", creationGas = 2_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(
            contract = "OrderFlowAllowance",
            creationGas = 2_000_000L,
            initcodeTransform = LambdaplexMixedOrderTypesTest.class)
    static SpecContract LAMBDAPLEX_HOOK;

    @FungibleToken(
            initialSupply = 10_000 * APPLES_SCALE,
            decimals = APPLES_DECIMALS,
            keys = {ADMIN_KEY, SUPPLY_KEY, FEE_SCHEDULE_KEY})
    static SpecFungibleToken APPLES;

    @FungibleToken(
            initialSupply = 10_000 * BANANAS_SCALE,
            decimals = BANANAS_DECIMALS,
            keys = {ADMIN_KEY, SUPPLY_KEY, FEE_SCHEDULE_KEY})
    static SpecFungibleToken BANANAS;

    @FungibleToken(
            initialSupply = 10_000 * USDC_SCALE,
            decimals = USDC_DECIMALS,
            keys = {ADMIN_KEY, SUPPLY_KEY, FEE_SCHEDULE_KEY})
    static SpecFungibleToken USDC;

    @Account(
            name = "marketMaker",
            tinybarBalance = THOUSAND_HBAR,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount MARKET_MAKER;

    @Account(
            name = "party",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount PARTY;

    @Account(
            name = "counterparty",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount COUNTERPARTY;

    @Account(name = "feeCollector", maxAutoAssociations = 3)
    static SpecAccount FEE_COLLECTOR;

    private final LambdaplexVerbs lv = new LambdaplexVerbs(HOOK_ID, "feeCollector");

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(
                MOCK_SUPRA_REGISTRY.getInfo(),
                LAMBDAPLEX_HOOK.getInfo(),
                APPLES.getInfo(),
                BANANAS.getInfo(),
                USDC.getInfo(),
                MOCK_SUPRA_REGISTRY.call("registerPair", BigInteger.ONE, APPLES, USDC),
                MOCK_SUPRA_REGISTRY.call("registerPair", BigInteger.TWO, BANANAS, USDC),
                MARKET_MAKER.getInfo(),
                PARTY.getInfo(),
                COUNTERPARTY.getInfo(),
                FEE_COLLECTOR.getInfo(),
                // Initialize everyone's token balances
                cryptoTransfer(
                        moving(applesUnits(1000), APPLES.name())
                                .between(APPLES.treasury().name(), MARKET_MAKER.name()),
                        moving(bananasUnits(1000), BANANAS.name())
                                .between(BANANAS.treasury().name(), MARKET_MAKER.name()),
                        moving(usdcUnits(1000), USDC.name())
                                .between(USDC.treasury().name(), MARKET_MAKER.name())),
                cryptoTransfer(
                        moving(applesUnits(100), APPLES.name())
                                .between(APPLES.treasury().name(), PARTY.name()),
                        moving(bananasUnits(100), BANANAS.name())
                                .between(BANANAS.treasury().name(), PARTY.name()),
                        moving(usdcUnits(100), USDC.name())
                                .between(USDC.treasury().name(), PARTY.name())),
                cryptoTransfer(
                        moving(applesUnits(100), APPLES.name())
                                .between(APPLES.treasury().name(), COUNTERPARTY.name()),
                        moving(bananasUnits(100), BANANAS.name())
                                .between(BANANAS.treasury().name(), COUNTERPARTY.name()),
                        moving(usdcUnits(100), USDC.name())
                                .between(USDC.treasury().name(), COUNTERPARTY.name())));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsMixedLimitAndMarketFullFillNoFees() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.50),
                        quantity(1.2)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.60),
                        quantity(1.8),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.56),
                        quantity(3.0)),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingSeller(
                                MARKET_MAKER,
                                leg(quantity(1.8), averagePrice(0.60), makerMarketSalt),
                                leg(quantity(1.2), averagePrice(0.50), makerLimitSalt)),
                        takingBuyer(PARTY, quantity(3.0), price(0.56), partyBuySalt)),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerMarketSalt),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerLimitSalt),
                lv.assertNoSuchOrder(PARTY.name(), partyBuySalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsMixedLimitAndMarketOrderingMatters() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(2.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(2.0)),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(2.0),
                                        averagePrice(1.00),
                                        makerLimitSalt,
                                        makerMarketSalt),
                                takingBuyer(PARTY, quantity(2.0), averagePrice(1.00), partyBuySalt))
                        .via("badOrderingTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(2.0), averagePrice(1.00), makerMarketSalt, makerLimitSalt),
                        takingBuyer(PARTY, quantity(2.0), averagePrice(1.00), partyBuySalt)),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerMarketSalt),
                lv.assertNoSuchOrder(PARTY.name(), partyBuySalt),
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerLimitSalt,
                        bd -> assertEquals(
                                0,
                                notional(1.0).compareTo(bd),
                                "Wrong SELL out token amount after mixed batch fill: expected one HBAR, got " + bd)));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsMixedRevertsWhenProposedDebitExceedsTotalAuthorized() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(2.0),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(4.0)),
                // Mixed maker orders authorize 3 HBAR total; proposing 3.1 HBAR debit must fail.
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(3.1),
                                        averagePrice(1.00),
                                        makerMarketSalt,
                                        makerLimitSalt),
                                takingBuyer(PARTY, quantity(3.1), averagePrice(1.00), partyBuySalt))
                        .via("mixedExcessDebitTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(st.remaining == 0, "debit too high")
                assertFirstError("mixedExcessDebitTx", "debit too high"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsMixedRevertsWhenProposedCreditLessThanTotalAuthorized() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(2.0),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(3.0)),
                // Mixed maker orders require 3.00 USDC credit for 3 HBAR; proposing 2.97 must fail.
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(3.0),
                                        averagePrice(0.99),
                                        makerMarketSalt,
                                        makerLimitSalt),
                                takingBuyer(PARTY, quantity(3.0), averagePrice(0.99), partyBuySalt))
                        .via("mixedUnderCreditTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertFirstError("mixedUnderCreditTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsMixedRevertsWhenFeeExceedsPermittedBps() {
        final var makerSellSalt = randomB64Salt();
        final var partyLimitBuySalt = randomB64Salt();
        final var partyMarketBuySalt = randomB64Salt();
        return hapiTest(
                // Give maker enough fee tolerance so maker-side validation passes.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        100,
                        price(0.10),
                        quantity(10.0)),
                lv.placeLimitOrder(
                        PARTY,
                        partyLimitBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(4.0)),
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(6.0),
                        0,
                        TimeInForce.IOC),
                // Two mixed zero-tolerance BUY orders in one batch invocation under taker fees.
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(10.0), averagePrice(0.10), makerSellSalt),
                                takingBuyer(
                                        PARTY,
                                        quantity(10.0),
                                        averagePrice(0.10),
                                        partyMarketBuySalt,
                                        partyLimitBuySalt))
                        .via("mixedFeeTooHighTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("mixedFeeTooHighTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHtsHtsMixedStopMarketRevertsWhenConfiguredMinFillNotMet() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        final var limitSellSalt = randomB64Salt();
        return hapiTest(
                // Resting liquidity: maker bids for only 8 APPLES.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(8.0)),
                // STOP_MARKET configured as fill-or-kill (minFill = 100%).
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        StopDirection.GT,
                        quantity(10.0),
                        10,
                        true),
                // Mixed multiple: include an additional non-stop order in the same seller batch.
                lv.placeLimitOrder(
                        PARTY,
                        limitSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(1.0)),
                // Trigger the stop via oracle proof in the fill path.
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, BigInteger.ONE, price(2.10), 1),
                lv.settleFillsWithOracleProofsNoFees(
                                APPLES,
                                USDC,
                                Map.of(stopSellSalt, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                                makingBuyer(MARKET_MAKER, quantity(8.0), averagePrice(1.80), makerBuySalt),
                                takingSeller(PARTY, quantity(8.0), averagePrice(1.80), stopSellSalt, limitSellSalt))
                        .via("mixedStopMinFillTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(take * BIPS >= q * mfb, "min fill")
                assertFirstError("mixedStopMinFillTx", "min fill"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsThreePartyMixedLimitAndMarketWithFees() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyMarketBuySalt = randomB64Salt();
        final var counterpartyLimitBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.00),
                        quantity(1.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.00),
                        quantity(2.0),
                        0,
                        TimeInForce.IOC),
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.00),
                        quantity(1.5),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        COUNTERPARTY,
                        counterpartyLimitBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.00),
                        quantity(1.5)),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(
                                        MARKET_MAKER,
                                        leg(quantity(2.0), averagePrice(1.00), makerMarketSalt),
                                        leg(quantity(1.0), averagePrice(1.00), makerLimitSalt)),
                                takingBuyer(PARTY, quantity(1.5), averagePrice(1.00), partyMarketBuySalt),
                                takingBuyer(COUNTERPARTY, quantity(1.5), averagePrice(1.00), counterpartyLimitBuySalt))
                        .via("threePartyHbarHtsTx"),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerMarketSalt),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerLimitSalt),
                lv.assertNoSuchOrder(PARTY.name(), partyMarketBuySalt),
                lv.assertNoSuchOrder(COUNTERPARTY.name(), counterpartyLimitBuySalt),
                getTxnRecord("threePartyHbarHtsTx")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(3.0), 8) * TAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3.0), USDC_DECIMALS) * MAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHtsHtsThreePartyMixedLimitAndMarketWithFees() {
        final var makerLimitSalt = randomB64Salt();
        final var makerMarketSalt = randomB64Salt();
        final var partyMarketBuySalt = randomB64Salt();
        final var counterpartyLimitBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitSalt,
                        APPLES,
                        BANANAS,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(2.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketSalt,
                        APPLES,
                        BANANAS,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(3.00),
                        quantity(2.0),
                        0,
                        TimeInForce.IOC),
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySalt,
                        APPLES,
                        BANANAS,
                        Side.BUY,
                        distantExpiry(),
                        TAKER_BPS,
                        price(2.50),
                        quantity(1.5),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        COUNTERPARTY,
                        counterpartyLimitBuySalt,
                        APPLES,
                        BANANAS,
                        Side.BUY,
                        distantExpiry(),
                        TAKER_BPS,
                        price(2.50),
                        quantity(2.5)),
                lv.settleFills(
                                APPLES,
                                BANANAS,
                                FEES,
                                makingSeller(
                                        MARKET_MAKER,
                                        leg(quantity(2.0), averagePrice(3.00), makerMarketSalt),
                                        leg(quantity(2.0), averagePrice(2.00), makerLimitSalt)),
                                takingBuyer(PARTY, quantity(1.5), averagePrice(2.50), partyMarketBuySalt),
                                takingBuyer(COUNTERPARTY, quantity(2.5), averagePrice(2.50), counterpartyLimitBuySalt))
                        .via("threePartyHtsHtsTx"),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerMarketSalt),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerLimitSalt),
                lv.assertNoSuchOrder(PARTY.name(), partyMarketBuySalt),
                lv.assertNoSuchOrder(COUNTERPARTY.name(), counterpartyLimitBuySalt),
                getTxnRecord("threePartyHtsHtsTx")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(1.5), APPLES_DECIMALS) * TAKER_BPS / 10_000
                                                        + inBaseUnits(quantity(2.5), APPLES_DECIMALS)
                                                                * TAKER_BPS
                                                                / 10_000)
                                        .including(
                                                BANANAS.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(10.0), BANANAS_DECIMALS) * MAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> batchOrderHbarHtsThreePartyMixedBuyerBatchPartialFillNoFees() {
        final var makerLimitBuySalt = randomB64Salt();
        final var makerMarketBuySalt = randomB64Salt();
        final var partyLimitSellSalt = randomB64Salt();
        final var counterpartyMarketSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerLimitBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(2.0)),
                lv.placeMarketOrder(
                        MARKET_MAKER,
                        makerMarketBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0),
                        0,
                        TimeInForce.IOC),
                lv.placeLimitOrder(
                        PARTY,
                        partyLimitSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0)),
                lv.placeMarketOrder(
                        COUNTERPARTY,
                        counterpartyMarketSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.00),
                        quantity(1.0),
                        0,
                        TimeInForce.IOC),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingBuyer(
                                MARKET_MAKER,
                                leg(quantity(1.0), averagePrice(1.00), makerMarketBuySalt),
                                leg(quantity(1.0), averagePrice(1.00), makerLimitBuySalt)),
                        takingSeller(PARTY, quantity(1.0), averagePrice(1.00), partyLimitSellSalt),
                        takingSeller(COUNTERPARTY, quantity(1.0), averagePrice(1.00), counterpartyMarketSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerMarketBuySalt),
                lv.assertNoSuchOrder(PARTY.name(), partyLimitSellSalt),
                lv.assertNoSuchOrder(COUNTERPARTY.name(), counterpartyMarketSellSalt),
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerLimitBuySalt,
                        bd -> assertEquals(
                                0,
                                notional(1.0).compareTo(bd),
                                "Wrong BUY out token amount after mixed batch fill: expected $1.00, got " + bd)));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> customFeesNotAllowed() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                cryptoCreate("customFeeCollector"),
                tokenAssociate("customFeeCollector", List.of(USDC.name(), BANANAS.name())),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10)),
                // Taker places market order to buy 10 HBAR for $0.10 each, no fee tolerance
                lv.placeMarketOrder(
                        PARTY,
                        buySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10),
                        0,
                        TimeInForce.IOC),
                tokenFeeScheduleUpdate(USDC.name())
                        .withCustom(
                                fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.of(100L), "customFeeCollector")),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("netOfTransfersFractionalTx"),
                // require(proposedTransfers.customFee.tokens.length == 0, "cf tokens present")
                assertFirstError("netOfTransfersFractionalTx", "cf tokens present"),
                tokenFeeScheduleUpdate(USDC.name())
                        .withCustom(fractionalFee(1L, 10L, 1L, OptionalLong.of(100L), "customFeeCollector")),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("fractionalTx"),
                // require(proposedTransfers.customFee.tokens.length == 0, "cf tokens present")
                assertFirstError("fractionalTx", "cf tokens present"),
                tokenFeeScheduleUpdate(USDC.name()).withCustom(fixedHtsFee(1L, BANANAS.name(), "customFeeCollector")),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("fixedHtsTx"),
                // require(proposedTransfers.customFee.tokens.length == 0, "cf tokens present")
                assertFirstError("fixedHtsTx", "cf tokens present"),
                tokenFeeScheduleUpdate(USDC.name()).withCustom(fixedHbarFee(1L, "customFeeCollector")),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("hbarFixedTx"),
                // require(proposedTransfers.customFee.hbarAdjustments.length == 0, "cf hbar present")
                assertFirstError("hbarFixedTx", "cf hbar present"),
                // Restore baseline USDC fee schedule for subsequent tests in this class
                tokenFeeScheduleUpdate(USDC.name()));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> nftTransfersNotAllowed(
            @NonFungibleToken(
                            keys = {SUPPLY_KEY},
                            numPreMints = 1)
                    SpecNonFungibleToken nonFungibleToken) {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                PARTY.associateTokens(nonFungibleToken),
                MARKET_MAKER.associateTokens(nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken.name(), 1)
                        .between(nonFungibleToken.treasury().name(), PARTY.name())),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10)),
                lv.placeMarketOrder(
                        PARTY,
                        buySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10),
                        0,
                        TimeInForce.IOC),
                sourcingContextual(spec -> lv.settleBodyTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                b -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(spec.registry().getTokenID(nonFungibleToken.name()))
                                        .addNftTransfers(NftTransfer.newBuilder()
                                                .setSenderAccountID(
                                                        spec.registry().getAccountID(PARTY.name()))
                                                .setReceiverAccountID(
                                                        spec.registry().getAccountID(MARKET_MAKER.name()))
                                                .setSerialNumber(1)
                                                .build())
                                        .build()),
                                makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt))
                        .signedBy(DEFAULT_PAYER, PARTY.name())
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("nftTransferTx")),
                assertFirstError("nftTransferTx", "nft"));
    }

    // --- InitcodeTransform ---
    @Override
    public String transformHexed(@NonNull final HapiSpec spec, @NonNull final String initcode) {
        var registryAddress = asLongZeroAddress(spec.registry()
                        .getContractId(MOCK_SUPRA_REGISTRY.name())
                        .getContractNum())
                .toHexString()
                .toLowerCase();
        if (registryAddress.startsWith("0x")) {
            registryAddress = registryAddress.substring(2);
        }
        return initcode.replace(REGISTRY_ADDRESS_TPL, registryAddress);
    }

    private static long applesUnits(long apples) {
        return apples * APPLES_SCALE;
    }

    private static long bananasUnits(long bananas) {
        return bananas * BANANAS_SCALE;
    }

    private static long usdcUnits(long usdc) {
        return usdc * USDC_SCALE;
    }
}
