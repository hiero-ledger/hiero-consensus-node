// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195.lambdaplex;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.tokenChangeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingHbarCredit;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenBalanceSnapshot;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.FillParty.leg;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.FillParty.makingBuyer;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.FillParty.takingSeller;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.HBAR;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.assertFirstError;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.averagePrice;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.distantExpiry;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.LambdaplexVerbs.inBaseUnits;
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
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests exercising the Lambdaplex protocol hook, with an emphasis on stop orders. (Has some intentional mild
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
public class LambdaplexStopOrderTypesTest implements InitcodeTransform {
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

    private static final BigInteger APPLES_USDC_PAIR_ID = BigInteger.ONE;
    private static final BigInteger BANANAS_USDC_PAIR_ID = BigInteger.TWO;
    private static final BigInteger HBAR_USDC_PAIR_ID = BigInteger.valueOf(3L);
    private static final BigInteger UNSUPPORTED_PAIR_ID = BigInteger.valueOf(999L);

    private static final byte STOP_LIMIT_LT = 2;
    private static final byte STOP_MARKET_LT = 3;
    private static final byte STOP_LIMIT_GT = 4;
    private static final byte STOP_MARKET_GT = 5;

    private static final Bytes MOCK_ORACLE_PROOF = Bytes.wrap(new byte[] {(byte) 0xa1, (byte) 0xb2, (byte) 0xc3});

    @Contract(contract = "MockSupraRegistry", creationGas = 2_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(
            contract = "OrderFlowAllowance",
            creationGas = 2_000_000L,
            initcodeTransform = LambdaplexStopOrderTypesTest.class)
    static SpecContract LAMBDAPLEX_HOOK;

    @FungibleToken(initialSupply = 10_000 * APPLES_SCALE, decimals = APPLES_DECIMALS)
    static SpecFungibleToken APPLES;

    @FungibleToken(initialSupply = 10_000 * BANANAS_SCALE, decimals = BANANAS_DECIMALS)
    static SpecFungibleToken BANANAS;

    @FungibleToken(initialSupply = 10_000 * USDC_SCALE, decimals = USDC_DECIMALS)
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
                MOCK_SUPRA_REGISTRY.call("registerPair", APPLES_USDC_PAIR_ID, APPLES, USDC),
                MOCK_SUPRA_REGISTRY.call("registerPair", BANANAS_USDC_PAIR_ID, BANANAS, USDC),
                MOCK_SUPRA_REGISTRY.call("registerPair", HBAR_USDC_PAIR_ID, HBAR, USDC),
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
    final Stream<DynamicTest> hbarHtsStopTriggerAndImmediateFullFillNoFeeInRangeSlippage() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(10)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.settleDataTransformedFillsNoFees(
                        HBAR,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(10), averagePrice(0.09), makerBuySalt),
                        takingSeller(PARTY, quantity(10), averagePrice(0.09), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopTriggerAndImmediateSatisficingPartialFillWithFeeInRangeSlippage() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("partyHbarBeforeStopPartial", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.09),
                        quantity(9)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.settleDataTransformedFills(
                                HBAR,
                                USDC,
                                FEES,
                                LambdaplexStopOrderTypesTest::withProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(9), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(9), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("hbarHtsStopPartialFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("partyHbarBeforeStopPartial", -9 * ONE_HBAR)),
                getTxnRecord("hbarHtsStopPartialFill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(9), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.81), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsSingleStopMarketOrderRevertsWhenConfiguredMinFillIsNotMet() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                // Resting liquidity: 8 HBAR bid at $0.09 each.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(8)),
                // Single STOP_MARKET order with fill-or-kill enabled, so minFill is 100%.
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        true),
                // Trigger the stop via oracle proof in the settle path.
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                // Only 80% fill (8/10 HBAR), so minFill should reject.
                lv.settleDataTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                LambdaplexStopOrderTypesTest::withProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(8), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(8), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("stopMinFillTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(take * BIPS >= q * mfb, "min fill")
                assertFirstError("stopMinFillTx", "min fill"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopPokeConvertsOrderToMarket() {
        final var stopSellSalt = randomB64Salt();
        final var makerBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(10).compareTo(bd),
                                "Wrong STOP order amount after poke: expected 10 HBAR, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(10)),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(10), averagePrice(0.09), makerBuySalt),
                        takingSeller(PARTY, quantity(10), averagePrice(0.09), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopTriggerAndImmediateFullFillNoFeeInRangeSlippage() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(3)),
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
                        quantity(3),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleDataTransformedFillsNoFees(
                        APPLES,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(1.80), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopTriggerAndImmediateSatisficingPartialFillWithFeeInRangeSlippage() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                tokenBalanceSnapshot(APPLES.name(), "partyApplesBeforeStopPartial", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.80),
                        quantity(2)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        StopDirection.GT,
                        quantity(3),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleDataTransformedFills(
                                APPLES,
                                USDC,
                                FEES,
                                LambdaplexStopOrderTypesTest::withProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(2), averagePrice(1.80), makerBuySalt),
                                takingSeller(PARTY, quantity(2), averagePrice(1.80), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("htsHtsStopPartialFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTokenBalance(
                                APPLES.name(),
                                tokenChangeFromSnapshot(
                                        APPLES.name(),
                                        "partyApplesBeforeStopPartial",
                                        inBaseUnits(quantity(-2), APPLES_DECIMALS))),
                getTxnRecord("htsHtsStopPartialFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(2), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3.60), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopPokeConvertsOrderToMarket() {
        final var stopSellSalt = randomB64Salt();
        final var makerBuySalt = randomB64Salt();
        return hapiTest(
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
                        quantity(3),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(3).compareTo(bd),
                                "Wrong STOP order amount after poke: expected 3 APPLES, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(3)),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(1.80), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopLimitTriggerAndImmediateFullFillNoFee() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(10)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.09),
                        StopDirection.GT,
                        quantity(10)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.5), 1),
                lv.settleDataTransformedFillsNoFees(
                        HBAR,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(10), averagePrice(0.09), makerBuySalt),
                        takingSeller(PARTY, quantity(10), averagePrice(0.09), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopLimitTriggerAndImmediatePartialFillNoFeeConvertedWriteback() {
        final var makerBuySalt = randomB64Salt();
        final var makerBuyRemainderSalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(9)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.09),
                        StopDirection.GT,
                        quantity(10)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.5), 1),
                lv.settleDataTransformedFillsNoFees(
                        HBAR,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(9), averagePrice(0.09), makerBuySalt),
                        takingSeller(PARTY, quantity(9), averagePrice(0.09), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after conversion: expected 1 HBAR, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuyRemainderSalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(1)),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(1), averagePrice(0.09), makerBuyRemainderSalt),
                        takingSeller(PARTY, quantity(1), averagePrice(0.09), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuyRemainderSalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopLimitTriggerAndImmediatePartialFillWithFeeConvertedWriteback() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("partyHbarBeforeStopLimitPartial", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.09),
                        quantity(9)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(0.09),
                        price(0.08),
                        StopDirection.LT,
                        quantity(10)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.settleDataTransformedFills(
                                HBAR,
                                USDC,
                                FEES,
                                LambdaplexStopOrderTypesTest::withProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(9), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(9), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("hbarHtsStopLimitPartialFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after conversion: expected 1 HBAR, got " + bd)),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("partyHbarBeforeStopLimitPartial", -9 * ONE_HBAR)),
                getTxnRecord("hbarHtsStopLimitPartialFill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(9), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.81), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> hbarHtsStopLimitPokeConvertsOrderToLimitFullQuantity() {
        final var stopSellSalt = randomB64Salt();
        final var makerBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.08),
                        StopDirection.LT,
                        quantity(10)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(10).compareTo(bd),
                                "Wrong STOP_LIMIT amount after poke: expected 10 HBAR, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(10)),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(10), averagePrice(0.09), makerBuySalt),
                        takingSeller(PARTY, quantity(10), averagePrice(0.09), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopLimitTriggerAndImmediateFullFillNoFee() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(3)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(3)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleDataTransformedFillsNoFees(
                        APPLES,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(1.80), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopLimitTriggerAndImmediatePartialFillNoFeeConvertedRemainder() {
        final var makerBuySalt = randomB64Salt();
        final var makerBuyRemainderSalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(2)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(3)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleDataTransformedFillsNoFees(
                        APPLES,
                        USDC,
                        LambdaplexStopOrderTypesTest::withProofIfStop,
                        makingBuyer(MARKET_MAKER, quantity(2), averagePrice(1.80), makerBuySalt),
                        takingSeller(PARTY, quantity(2), averagePrice(1.80), stopSellSalt)
                                .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after conversion: expected 1 APPLES, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuyRemainderSalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(1)),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(1), averagePrice(1.80), makerBuyRemainderSalt),
                        takingSeller(PARTY, quantity(1), averagePrice(1.80), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuyRemainderSalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopLimitTriggerAndImmediatePartialFillWithFeeConvertedRemainder() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                tokenBalanceSnapshot(APPLES.name(), "partyApplesBeforeStopLimitPartial", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.80),
                        quantity(2)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(3)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleDataTransformedFills(
                                APPLES,
                                USDC,
                                FEES,
                                LambdaplexStopOrderTypesTest::withProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(2), averagePrice(1.80), makerBuySalt),
                                takingSeller(PARTY, quantity(2), averagePrice(1.80), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("htsHtsStopLimitPartialFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after conversion: expected 1 APPLES, got " + bd)),
                getAccountBalance(PARTY.name())
                        .hasTokenBalance(
                                APPLES.name(),
                                tokenChangeFromSnapshot(
                                        APPLES.name(),
                                        "partyApplesBeforeStopLimitPartial",
                                        inBaseUnits(quantity(-2), APPLES_DECIMALS))),
                getTxnRecord("htsHtsStopLimitPartialFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(2), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3.60), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> htsHtsStopLimitPokeConvertsOrderToLimitFullQuantity() {
        final var stopSellSalt = randomB64Salt();
        final var makerBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(3)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF),
                lv.rebindSaltAfterStopConversion(stopSellSalt),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0,
                                notional(3).compareTo(bd),
                                "Wrong STOP_LIMIT amount after poke: expected 3 APPLES, got " + bd)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(3)),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(1.80), stopSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSalt));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHtsHtsStopLimitGtTriggersToFullPlusPartialNoFees() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(3)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltA,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltB,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltC,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        price(1.80),
                        StopDirection.GT,
                        quantity(2)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleFillsWithOracleProofsNoFees(
                        APPLES,
                        USDC,
                        Map.of(
                                stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                        takingSeller(
                                PARTY,
                                leg(quantity(1), averagePrice(1.80), stopSellSaltA),
                                leg(quantity(1), averagePrice(1.80), stopSellSaltB),
                                leg(quantity(1), averagePrice(1.80), stopSellSaltC))),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.rebindSaltAfterStopConversion(stopSellSaltC),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSaltC,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after multi-leg conversion: expected 1 APPLES, got "
                                        + bd)));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHtsHtsStopLimitLtTriggersToFullPlusPartialWithFees() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.80),
                        quantity(3)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltA,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.80),
                        price(1.70),
                        StopDirection.LT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltB,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.80),
                        price(1.70),
                        StopDirection.LT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltC,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.80),
                        price(1.70),
                        StopDirection.LT,
                        quantity(2)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleFillsWithOracleProofs(
                                APPLES,
                                USDC,
                                FEES,
                                Map.of(
                                        stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                        stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                        stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                                makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.80), makerBuySalt),
                                takingSeller(
                                        PARTY,
                                        leg(quantity(1), averagePrice(1.80), stopSellSaltA),
                                        leg(quantity(1), averagePrice(1.80), stopSellSaltB),
                                        leg(quantity(1), averagePrice(1.80), stopSellSaltC)))
                        .via("htsHtsMultiStopLimitPartialWithFees"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.rebindSaltAfterStopConversion(stopSellSaltC),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSaltC,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after multi-leg conversion: expected 1 APPLES, got " + bd)),
                getTxnRecord("htsHtsMultiStopLimitPartialWithFees")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(5.40), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHbarHtsStopLimitGtTriggersToFullPlusPartialNoFees() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(3)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltA,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.09),
                        StopDirection.GT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltB,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.09),
                        StopDirection.GT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltC,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        price(0.09),
                        StopDirection.GT,
                        quantity(2)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.5), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.5), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.5), 1),
                lv.settleFillsWithOracleProofsNoFees(
                        HBAR,
                        USDC,
                        Map.of(
                                stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(0.09), makerBuySalt),
                        takingSeller(
                                PARTY,
                                leg(quantity(1), averagePrice(0.09), stopSellSaltA),
                                leg(quantity(1), averagePrice(0.09), stopSellSaltB),
                                leg(quantity(1), averagePrice(0.09), stopSellSaltC))),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.rebindSaltAfterStopConversion(stopSellSaltC),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSaltC,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after multi-leg conversion: expected 1 HBAR, got " + bd)));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHbarHtsStopLimitLtTriggersToFullPlusPartialWithFees() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.09),
                        quantity(3)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltA,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(0.09),
                        price(0.08),
                        StopDirection.LT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltB,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(0.09),
                        price(0.08),
                        StopDirection.LT,
                        quantity(1)),
                lv.placeStopLimitOrder(
                        PARTY,
                        stopSellSaltC,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(0.09),
                        price(0.08),
                        StopDirection.LT,
                        quantity(2)),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.settleFillsWithOracleProofs(
                                HBAR,
                                USDC,
                                FEES,
                                Map.of(
                                        stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                        stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                        stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                                makingBuyer(MARKET_MAKER, quantity(3), averagePrice(0.09), makerBuySalt),
                                takingSeller(
                                        PARTY,
                                        leg(quantity(1), averagePrice(0.09), stopSellSaltA),
                                        leg(quantity(1), averagePrice(0.09), stopSellSaltB),
                                        leg(quantity(1), averagePrice(0.09), stopSellSaltC)))
                        .via("hbarHtsMultiStopLimitPartialWithFees"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.rebindSaltAfterStopConversion(stopSellSaltC),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSaltC,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong STOP_LIMIT remainder after multi-leg conversion: expected 1 HBAR, got " + bd)),
                getTxnRecord("hbarHtsMultiStopLimitPartialWithFees")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(3), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.27), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHbarHtsStopMarketLtTriggersToFullPlusSatisficingPartialNoFeesAllDeleted() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(6)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltA,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.08),
                        StopDirection.LT,
                        quantity(2),
                        20,
                        false),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltB,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.08),
                        StopDirection.LT,
                        quantity(2),
                        20,
                        false),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltC,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.08),
                        StopDirection.LT,
                        quantity(3),
                        20,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(9.0), 1),
                lv.settleFillsWithOracleProofsNoFees(
                        HBAR,
                        USDC,
                        Map.of(
                                stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                        makingBuyer(MARKET_MAKER, quantity(6), averagePrice(0.09), makerBuySalt),
                        takingSeller(
                                PARTY,
                                leg(quantity(2), averagePrice(0.09), stopSellSaltA),
                                leg(quantity(2), averagePrice(0.09), stopSellSaltB),
                                leg(quantity(2), averagePrice(0.09), stopSellSaltC))),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.assertNoSuchOrder(PARTY, stopSellSaltC));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> multipleHtsHtsStopMarketGtTriggersToFullPlusSatisficingPartialNoFeesAllDeleted() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSaltA = randomB64Salt();
        final var stopSellSaltB = randomB64Salt();
        final var stopSellSaltC = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.80),
                        quantity(6)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltA,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        StopDirection.GT,
                        quantity(2),
                        10,
                        false),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltB,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        StopDirection.GT,
                        quantity(2),
                        10,
                        false),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSaltC,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        StopDirection.GT,
                        quantity(3),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.enqueueProofVerify(MOCK_SUPRA_REGISTRY, APPLES_USDC_PAIR_ID, price(2.10), 1),
                lv.settleFillsWithOracleProofsNoFees(
                        APPLES,
                        USDC,
                        Map.of(
                                stopSellSaltA, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltB, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF),
                                stopSellSaltC, LambdaplexVerbs.OracleProofSpec.exact(MOCK_ORACLE_PROOF)),
                        makingBuyer(MARKET_MAKER, quantity(6), averagePrice(1.80), makerBuySalt),
                        takingSeller(
                                PARTY,
                                leg(quantity(2), averagePrice(1.80), stopSellSaltA),
                                leg(quantity(2), averagePrice(1.80), stopSellSaltB),
                                leg(quantity(2), averagePrice(1.80), stopSellSaltC))),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, stopSellSaltA),
                lv.assertNoSuchOrder(PARTY, stopSellSaltB),
                lv.assertNoSuchOrder(PARTY, stopSellSaltC));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsIfOracleRegistryDoesNotSupportPair() {
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, UNSUPPORTED_PAIR_ID, price(11.0), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF)
                        .via("unsupportedPairTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("unsupportedPairTx", "oracle: wrong pair"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsOnMissingOracleProofLength() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(1)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(1),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.settleDataTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                LambdaplexStopOrderTypesTest::withMissingProofLengthIfStop,
                                makingBuyer(MARKET_MAKER, quantity(1), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(1), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("missingProofLenTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("missingProofLenTx", "len oob"),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0, notional(1).compareTo(bd), "Order should remain open after malformed proof")));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsOnExcessiveOracleProofLength() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(1)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(1),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.settleDataTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                LambdaplexStopOrderTypesTest::withExcessiveProofLengthIfStop,
                                makingBuyer(MARKET_MAKER, quantity(1), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(1), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("excessiveProofLenTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("excessiveProofLenTx", "proof oob"),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0, notional(1).compareTo(bd), "Order should remain open after malformed proof")));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsOnTruncatedOracleProof() {
        final var makerBuySalt = randomB64Salt();
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        quantity(1)),
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(1),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 1),
                lv.settleDataTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                LambdaplexStopOrderTypesTest::withTruncatedProofIfStop,
                                makingBuyer(MARKET_MAKER, quantity(1), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(1), averagePrice(0.09), stopSellSalt)
                                        .withGasLimit(LambdaplexVerbs.ORACLE_HOOK_GAS))
                        .via("truncatedProofTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("truncatedProofTx", "proof oob"),
                lv.assertOrderAmount(
                        PARTY,
                        stopSellSalt,
                        bd -> assertEquals(
                                0, notional(1).compareTo(bd), "Order should remain open after malformed proof")));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsOnStaleOracleTimestamp() {
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(11.0), 61),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF)
                        .via("staleOracleTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("staleOracleTx", "oracle: stale"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsToTriggerLtStopIfOrderPriceAboveOracle() {
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.11),
                        StopDirection.LT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(10.0), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF)
                        .via("ltNotTriggeredTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("ltNotTriggeredTx", "op > tr"));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> failsToTriggerGtStopIfOrderPriceBelowOracle() {
        final var stopSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeStopMarketOrder(
                        PARTY,
                        stopSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.09),
                        StopDirection.GT,
                        quantity(10),
                        10,
                        false),
                lv.answerNextProofVerify(MOCK_SUPRA_REGISTRY, HBAR_USDC_PAIR_ID, price(10.0), 1),
                lv.pokeWithOracleProof(PARTY, stopSellSalt, MOCK_ORACLE_PROOF)
                        .via("gtNotTriggeredTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                assertFirstError("gtNotTriggeredTx", "op < tr"));
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

    private static Bytes withProofIfStop(@NonNull final Bytes data) {
        final byte orderType = data.getByte(0);
        if (isStopOrderType(orderType)) {
            return data.append(uint256Word(MOCK_ORACLE_PROOF.length())).append(MOCK_ORACLE_PROOF);
        }
        return data;
    }

    private static Bytes withMissingProofLengthIfStop(@NonNull final Bytes data) {
        final byte orderType = data.getByte(0);
        if (isStopOrderType(orderType)) {
            return data.append(MOCK_ORACLE_PROOF);
        }
        return data;
    }

    private static Bytes withExcessiveProofLengthIfStop(@NonNull final Bytes data) {
        final byte orderType = data.getByte(0);
        if (isStopOrderType(orderType)) {
            return data.append(uint256Word(MOCK_ORACLE_PROOF.length() + 64L)).append(MOCK_ORACLE_PROOF);
        }
        return data;
    }

    private static Bytes withTruncatedProofIfStop(@NonNull final Bytes data) {
        final byte orderType = data.getByte(0);
        if (isStopOrderType(orderType)) {
            final var truncatedProof = MOCK_ORACLE_PROOF.slice(0, MOCK_ORACLE_PROOF.length() - 1);
            return data.append(uint256Word(MOCK_ORACLE_PROOF.length())).append(truncatedProof);
        }
        return data;
    }

    private static boolean isStopOrderType(final byte orderType) {
        return orderType == STOP_LIMIT_LT
                || orderType == STOP_MARKET_LT
                || orderType == STOP_LIMIT_GT
                || orderType == STOP_MARKET_GT;
    }

    private static Bytes uint256Word(final long value) {
        final var bytes = BigInteger.valueOf(value).toByteArray();
        final var padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return Bytes.wrap(padded);
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
