// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195.lambdaplex;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertOwnerHasEvmHookSlotUsageChange;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordCurrentOwnerEvmHookSlotUsage;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenBalanceSnapshot;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
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
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests exercising the Lambdaplex protocol hook, focusing on the "core" limit and market order types. (Has some
 * intentional mild duplication with other test classes in this package to keep each test class more self-contained.)
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
public class LambdaplexCoreOrderTypesTest implements InitcodeTransform {
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

    @Contract(contract = "MockSupraRegistry", creationGas = 2_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(
            contract = "OrderFlowAllowance",
            creationGas = 2_000_000L,
            initcodeTransform = LambdaplexCoreOrderTypesTest.class)
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

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsFullFillOnMakerSpreadCrossNoFees() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        final var makerOrdersBefore = new AtomicLong();
        final var partyOrdersBefore = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerEvmHookSlotUsage(MARKET_MAKER.name(), makerOrdersBefore::set),
                recordCurrentOwnerEvmHookSlotUsage(PARTY.name(), partyOrdersBefore::set),
                // Market maker places a limit order to sell up to 10 HBAR for $0.10 each, no fee tolerance
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
                // Second market maker places order to buy 10 HBAR for $0.10 each, no fee tolerance
                lv.placeLimitOrder(
                        PARTY, buySalt, HBAR, USDC, Side.BUY, distantExpiry(), ZERO_BPS, price(0.10), quantity(10)),
                // Do a zero-fee settlement
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                        takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt)),
                // Both party "out token" limits were fully executed, so hook removes the orders
                assertOwnerHasEvmHookSlotUsageChange(MARKET_MAKER.name(), makerOrdersBefore, 0),
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), partyOrdersBefore, 0));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsFullFillOnTakerSpreadCrossNoFeesOrSlippage() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("makerHbar", MARKET_MAKER.name()),
                tokenBalanceSnapshot(USDC.name(), "makerUsdc", MARKET_MAKER.name()),
                balanceSnapshot("takerHbar", PARTY.name()),
                tokenBalanceSnapshot(USDC.name(), "takerUsdc", PARTY.name()),
                // Market maker places a limit order to sell up to 10 HBAR for $0.10 each
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
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                        takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, sellSalt),
                lv.assertNoSuchOrder(PARTY, buySalt),
                getAccountBalance(MARKET_MAKER.name())
                        .hasTinyBars(changeFromSnapshot("makerHbar", -10 * ONE_HBAR))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(), "makerUsdc", inBaseUnits(quantity(1), USDC_DECIMALS))),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("takerHbar", 10 * ONE_HBAR))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(), "takerUsdc", inBaseUnits(quantity(-1), USDC_DECIMALS))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsSingleLimitOrderRevertsWhenProposedDebitExceedsAuthorized() {
        final var makerSellSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(20)),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(11), averagePrice(0.10), makerSellSalt),
                                takingBuyer(PARTY, quantity(11), averagePrice(0.10), partyBuySalt))
                        .via("singleLimitExcessDebitTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(st.remaining == 0, "debit too high")
                assertFirstError("singleLimitExcessDebitTx", "debit too high"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMultipleLimitOrdersRevertWhenProposedDebitExceedsTotalAuthorized() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(3)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(2)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(20)),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(6),
                                        averagePrice(0.10),
                                        makerSellSaltOne,
                                        makerSellSaltTwo),
                                takingBuyer(PARTY, quantity(6), averagePrice(0.10), partyBuySalt))
                        .via("multiLimitExcessDebitTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(st.remaining == 0, "debit too high")
                assertFirstError("multiLimitExcessDebitTx", "debit too high"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsSingleLimitOrderRevertsWhenProposedCreditIsLessThanRequired() {
        final var makerSellSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(20)),
                // Maker needs exactly 1.00 USDC for 10 HBAR at the limit price; proposing 0.99 must fail.
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), averagePrice(0.099), makerSellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.099), partyBuySalt))
                        .via("singleLimitUnderCreditTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertFirstError("singleLimitUnderCreditTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMultipleLimitOrdersRevertWhenProposedCreditIsLessThanRequired() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(3)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(2)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(20)),
                // Combined maker fills need exactly 0.50 USDC for 5 HBAR at $0.10; proposing 0.495 must fail.
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(0.099),
                                        makerSellSaltOne,
                                        makerSellSaltTwo),
                                takingBuyer(PARTY, quantity(5), averagePrice(0.099), partyBuySalt))
                        .via("multiLimitUnderCreditTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertFirstError("multiLimitUnderCreditTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsFullFillOnTakerSpreadWithFeesNoSlippage() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("makerHbar", MARKET_MAKER.name()),
                tokenBalanceSnapshot(USDC.name(), "makerUsdc", MARKET_MAKER.name()),
                balanceSnapshot("takerHbar", PARTY.name()),
                tokenBalanceSnapshot(USDC.name(), "takerUsdc", PARTY.name()),
                // Market maker places a limit order to sell up to 10 HBAR for $0.10 each
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.10),
                        quantity(10)),
                lv.placeMarketOrder(
                        PARTY,
                        buySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        TAKER_BPS,
                        price(0.10),
                        quantity(10),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                        HBAR,
                        USDC,
                        FEES,
                        makingSeller(MARKET_MAKER, quantity(10), price(0.10), sellSalt),
                        takingBuyer(PARTY, quantity(10), averagePrice(0.10), buySalt)),
                getAccountBalance(MARKET_MAKER.name())
                        .hasTinyBars(changeFromSnapshot("makerHbar", -10 * ONE_HBAR))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(),
                                        "makerUsdc",
                                        inBaseUnits(quantity(1.0 - 1.0 * MAKER_BPS / 10_000), USDC_DECIMALS))),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(
                                changeFromSnapshot("takerHbar", (10 * ONE_HBAR - 10 * ONE_HBAR * TAKER_BPS / 10_000)))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(), "takerUsdc", inBaseUnits(quantity(-1), USDC_DECIMALS))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsFullFillOnTakerSpreadCrossNoFeesInRangeSlippage() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("makerHbar", MARKET_MAKER.name()),
                tokenBalanceSnapshot(USDC.name(), "makerUsdc", MARKET_MAKER.name()),
                balanceSnapshot("takerHbar", PARTY.name()),
                tokenBalanceSnapshot(USDC.name(), "takerUsdc", PARTY.name()),
                // Market maker places a limit order to sell up to 10 HBAR for $0.11 each
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.11),
                        quantity(10)),
                // Taker places market order to buy 10 HBAR for $0.10 each, slippage up to 10%
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
                        10,
                        TimeInForce.IOC),
                // Characterize meaning of slippage; it reduces required inToken, but does not add a buffer to outToken
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.11), sellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.11), buySalt))
                        .via("excessDebitTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(st.remaining == 0, "debit too high")
                assertSecondError("excessDebitTx", "debit too high"),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(8), price(0.12), sellSalt),
                                takingBuyer(PARTY, quantity(8), averagePrice(0.12), buySalt))
                        .via("excessSlippageTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("excessSlippageTx", "credit too low"),
                lv.settleFillsNoFees(
                        HBAR,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(9), price(0.11), sellSalt),
                        takingBuyer(PARTY, quantity(9), averagePrice(0.11), buySalt)),
                lv.assertOrderAmount(
                        MARKET_MAKER,
                        sellSalt,
                        bd -> assertEquals(
                                0,
                                notional(1).compareTo(bd),
                                "Wrong SELL out token amount after slippage: expected 1 HBAR, got " + bd)),
                lv.assertNoSuchOrder(PARTY, buySalt),
                getAccountBalance(MARKET_MAKER.name())
                        .hasTinyBars(changeFromSnapshot("makerHbar", -9 * ONE_HBAR))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(), "makerUsdc", inBaseUnits(quantity(0.99), USDC_DECIMALS))),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("takerHbar", 9 * ONE_HBAR))
                        .hasTokenBalance(
                                USDC.name(),
                                tokenChangeFromSnapshot(
                                        USDC.name(), "takerUsdc", inBaseUnits(quantity(-0.99), USDC_DECIMALS))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMarketFullFillWithFeesInRangeSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                // Maker bids 10 HBAR for $0.09 each
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.09),
                        quantity(10)),
                // Taker sells 10 HBAR at $0.10 reference with up to 10% slippage tolerance
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(0.10),
                        quantity(10),
                        10,
                        TimeInForce.IOC),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(10), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(10), averagePrice(0.09), marketSellSalt))
                        .via("hbarHtsFullFeeSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getTxnRecord("hbarHtsFullFeeSlipFill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(10), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.9), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMarketSatisficingPartialFillWithFeesNoSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("partyHbarBeforePartialNoSlip", PARTY.name()),
                // Maker bids for only 9 HBAR, so market order will be partially satisficed then deleted
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.10),
                        quantity(9)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(0.10),
                        quantity(10),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(9), averagePrice(0.10), makerBuySalt),
                                takingSeller(PARTY, quantity(9), averagePrice(0.10), marketSellSalt))
                        .via("hbarHtsPartialFeeNoSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("partyHbarBeforePartialNoSlip", -9 * ONE_HBAR)),
                getTxnRecord("hbarHtsPartialFeeNoSlipFill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(9), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.9), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMarketSatisficingPartialFillWithFeesInRangeSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                balanceSnapshot("partyHbarBeforePartialSlip", PARTY.name()),
                // Maker bids for only 9 HBAR at a 10%-worse price for the seller
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
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(0.10),
                        quantity(10),
                        10,
                        TimeInForce.IOC),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(9), averagePrice(0.09), makerBuySalt),
                                takingSeller(PARTY, quantity(9), averagePrice(0.09), marketSellSalt))
                        .via("hbarHtsPartialFeeSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTinyBars(changeFromSnapshot("partyHbarBeforePartialSlip", -9 * ONE_HBAR)),
                getTxnRecord("hbarHtsPartialFeeSlipFill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(9), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(0.81), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsSingleMarketOrderRevertsWhenConfiguredMinFillIsNotMet() {
        final var makerSellSalt = randomB64Salt();
        final var takerBuySalt = randomB64Salt();
        return hapiTest(
                // Resting liquidity: 8 HBAR for $0.10 each.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(8)),
                // Single MARKET order with FOK and 10% slippage. Here minFill is encoded as 90% (900_000 centi-bps).
                lv.placeMarketOrder(
                        PARTY,
                        takerBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10),
                        10,
                        TimeInForce.FOK),
                // Only 80% fill (0.8 USDC out of 1.0 USDC max debit), so minFill should reject.
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(8), averagePrice(0.10), makerSellSalt),
                                takingBuyer(PARTY, quantity(8), averagePrice(0.10), takerBuySalt))
                        .via("minFillTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(take * BIPS >= q * mfb, "min fill")
                assertSecondError("minFillTx", "min fill"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsSingleLimitOrderRevertsWhenFeeExceedsPermittedBps() {
        final var makerSellSalt = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
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
                        quantity(10)),
                // Zero fee tolerance on this single limit BUY should fail under taker fees.
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10)),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(10), averagePrice(0.10), makerSellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), partyBuySalt))
                        .via("singleLimitFeeTooHighTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("singleLimitFeeTooHighTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsSingleMarketOrderRevertsWhenFeeExceedsPermittedBps() {
        final var makerSellSalt = randomB64Salt();
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
                        quantity(10)),
                // Zero fee tolerance on this single market BUY should fail under taker fees.
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(10),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(10), averagePrice(0.10), makerSellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), partyMarketBuySalt))
                        .via("singleMarketFeeTooHighTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("singleMarketFeeTooHighTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMultipleLimitOrdersRevertWhenFeeExceedsPermittedBps() {
        final var makerSellSalt = randomB64Salt();
        final var partyBuySaltOne = randomB64Salt();
        final var partyBuySaltTwo = randomB64Salt();
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
                        quantity(10)),
                // Two zero-tolerance limit BUY orders in one batch invocation.
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySaltOne,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(4)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySaltTwo,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(6)),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(10), averagePrice(0.10), makerSellSalt),
                                takingBuyer(PARTY, quantity(10), averagePrice(0.10), partyBuySaltOne, partyBuySaltTwo))
                        .via("multiLimitFeeTooHighTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("multiLimitFeeTooHighTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> hbarHtsMultipleMarketOrdersRevertWhenFeeExceedsPermittedBps() {
        final var makerSellSalt = randomB64Salt();
        final var partyMarketBuySaltOne = randomB64Salt();
        final var partyMarketBuySaltTwo = randomB64Salt();
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
                        quantity(10)),
                // Two zero-tolerance market BUY orders in one batch invocation.
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySaltOne,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(4),
                        0,
                        TimeInForce.IOC),
                lv.placeMarketOrder(
                        PARTY,
                        partyMarketBuySaltTwo,
                        HBAR,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        ZERO_BPS,
                        price(0.10),
                        quantity(6),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(10), averagePrice(0.10), makerSellSalt),
                                takingBuyer(
                                        PARTY,
                                        quantity(10),
                                        averagePrice(0.10),
                                        partyMarketBuySaltOne,
                                        partyMarketBuySaltTwo))
                        .via("multiMarketFeeTooHighTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(sumInAbs + st.feeTotal >= st.needTotal, "credit too low")
                assertSecondError("multiMarketFeeTooHighTx", "credit too low"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketFullFillNoFeesNoSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(3)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(3),
                        0,
                        TimeInForce.IOC),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(2.00), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(2.00), marketSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketFullFillNoFeesInRangeSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.90),
                        quantity(3)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(3),
                        10,
                        TimeInForce.IOC),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.90), makerBuySalt),
                        takingSeller(PARTY, quantity(3), averagePrice(1.90), marketSellSalt)),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketFullFillWithFeesNoSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(3)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        quantity(3),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(3), averagePrice(2.00), makerBuySalt),
                                takingSeller(PARTY, quantity(3), averagePrice(2.00), marketSellSalt))
                        .via("htsHtsFullFeeNoSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getTxnRecord("htsHtsFullFeeNoSlipFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(6.00), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketFullFillWithFeesInRangeSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.90),
                        quantity(3)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        quantity(3),
                        10,
                        TimeInForce.IOC),
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(3), averagePrice(1.90), makerBuySalt),
                                takingSeller(PARTY, quantity(3), averagePrice(1.90), marketSellSalt))
                        .via("htsHtsFullFeeSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getTxnRecord("htsHtsFullFeeSlipFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(5.70), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketSatisficingPartialFillWithFeesNoSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                tokenBalanceSnapshot(APPLES.name(), "partyApplesBeforePartialNoSlip", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(2)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        quantity(3),
                        0,
                        TimeInForce.IOC),
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(2), averagePrice(2.00), makerBuySalt),
                                takingSeller(PARTY, quantity(2), averagePrice(2.00), marketSellSalt))
                        .via("htsHtsPartialFeeNoSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTokenBalance(
                                APPLES.name(),
                                tokenChangeFromSnapshot(
                                        APPLES.name(),
                                        "partyApplesBeforePartialNoSlip",
                                        inBaseUnits(quantity(-2), APPLES_DECIMALS))),
                getTxnRecord("htsHtsPartialFeeNoSlipFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(2), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(4.00), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsMarketSatisficingPartialFillWithFeesInRangeSlippageDeletesOrders() {
        final var makerBuySalt = randomB64Salt();
        final var marketSellSalt = randomB64Salt();
        return hapiTest(
                tokenBalanceSnapshot(APPLES.name(), "partyApplesBeforePartialSlip", PARTY.name()),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.90),
                        quantity(2)),
                lv.placeMarketOrder(
                        PARTY,
                        marketSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        iocExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        quantity(3),
                        10,
                        TimeInForce.IOC),
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingBuyer(MARKET_MAKER, quantity(2), averagePrice(1.90), makerBuySalt),
                                takingSeller(PARTY, quantity(2), averagePrice(1.90), marketSellSalt))
                        .via("htsHtsPartialFeeSlipFill"),
                lv.assertNoSuchOrder(MARKET_MAKER, makerBuySalt),
                lv.assertNoSuchOrder(PARTY, marketSellSalt),
                getAccountBalance(PARTY.name())
                        .hasTokenBalance(
                                APPLES.name(),
                                tokenChangeFromSnapshot(
                                        APPLES.name(),
                                        "partyApplesBeforePartialSlip",
                                        inBaseUnits(quantity(-2), APPLES_DECIMALS))),
                getTxnRecord("htsHtsPartialFeeSlipFill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(2), APPLES_DECIMALS) * MAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3.80), USDC_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHbarFullFillOnMakerSpreadCrossWithFees() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        final var makerOrdersBefore = new AtomicLong();
        final var partyOrdersBefore = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerEvmHookSlotUsage(MARKET_MAKER.name(), makerOrdersBefore::set),
                recordCurrentOwnerEvmHookSlotUsage(PARTY.name(), partyOrdersBefore::set),
                // Market maker places a limit order w/ maker fee tolerance of 12 bps (0.12%)
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        APPLES,
                        HBAR,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(12.00),
                        quantity(3)),
                // Second party places matching limit order w/ taker fee tolerance of 25 bps (0.25%)
                lv.placeLimitOrder(
                        PARTY, buySalt, APPLES, HBAR, Side.BUY, distantExpiry(), TAKER_BPS, price(12.00), quantity(3)),
                lv.settleFills(
                                APPLES,
                                HBAR,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(3), price(12.00), sellSalt),
                                takingBuyer(PARTY, quantity(3), averagePrice(12.00), buySalt))
                        .via("fill"),
                // Both party "out token" limits were fully executed, so hook removes the orders
                assertOwnerHasEvmHookSlotUsageChange(MARKET_MAKER.name(), makerOrdersBefore, 0),
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), partyOrdersBefore, 0),
                // Double-check fee transfers once
                getTxnRecord("fill")
                        .hasPriority(recordWith()
                                .transfers(includingHbarCredit(
                                        FEE_COLLECTOR.name(), inBaseUnits(quantity(36), 8) * MAKER_BPS / 10_000))
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3), APPLES_DECIMALS) * TAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchOrderHbarHtsFullFillsWithFeesOnMakerSpreadCross() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                // Two sells, 1.5 at $0.5, 1.8 at $0.6 -> total cost is $1.5*0.5 + $1.8*0.6 = $2.28
                // for an average price of $0.57 per HBAR
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.50),
                        quantity(1.5)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(0.60),
                        quantity(1.8)),
                // Buy 3.3 HBAR for $0.57 average price per
                lv.placeLimitOrder(
                        PARTY, buySalt, HBAR, USDC, Side.BUY, distantExpiry(), TAKER_BPS, price(0.57), quantity(3.3)),
                lv.settleFills(
                        HBAR,
                        USDC,
                        FEES,
                        makingSeller(
                                MARKET_MAKER, quantity(3.3), averagePrice(0.57), makerSellSaltOne, makerSellSaltTwo),
                        takingBuyer(PARTY, quantity(3.3), price(0.57), buySalt)),
                // All salts should be used up
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltOne),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltTwo),
                lv.assertNoSuchOrder(PARTY.name(), buySalt));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHbarPartialThenPartialThenFullFillsWithAndWithoutFeesOnMakerSpreadCross() {
        final var makerSalt = randomB64Salt();
        final var sellSaltOne = randomB64Salt();
        final var sellSaltTwo = randomB64Salt();
        final var sellSaltThree = randomB64Salt();
        return hapiTest(
                tokenBalanceSnapshot(APPLES.name(), "BEFORE", MARKET_MAKER.name()),
                // Buy up to 2 apples for 10 HBAR each
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSalt,
                        APPLES,
                        HBAR,
                        Side.BUY,
                        distantExpiry(),
                        MAKER_BPS,
                        price(10),
                        quantity(2)),
                // Sell one apple for 9 HBAR, no fees
                lv.placeLimitOrder(
                        PARTY, sellSaltOne, APPLES, HBAR, Side.SELL, distantExpiry(), ZERO_BPS, price(9), quantity(1)),
                lv.settleFillsNoFees(
                        APPLES,
                        HBAR,
                        takingSeller(PARTY, quantity(1), averagePrice(9), sellSaltOne),
                        makingBuyer(MARKET_MAKER, quantity(1), price(9), makerSalt)),
                // Now still buying up to 11 HBAR worth of apples
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerSalt,
                        bd -> assertEquals(
                                0,
                                notional(11).compareTo(bd),
                                "Wrong BUY out token amount after partial fill: expected 11 HBAR, got " + bd)),
                // Sell half an apple for 5 HBAR, no fees
                lv.placeLimitOrder(
                        PARTY,
                        sellSaltTwo,
                        APPLES,
                        HBAR,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(10),
                        quantity(0.5)),
                lv.settleFillsNoFees(
                        APPLES,
                        HBAR,
                        takingSeller(PARTY, quantity(0.5), averagePrice(10), sellSaltTwo),
                        makingBuyer(MARKET_MAKER, quantity(0.5), price(10), makerSalt)),
                // Now still buying up to 6 HBAR worth of apples
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerSalt,
                        bd -> assertEquals(
                                0,
                                notional(6).compareTo(bd),
                                "Wrong BUY out token amount after second partial fill: expected 6 HBAR, got " + bd)),
                // Sell an entire apple for 6 HBAR, taker fees
                lv.placeLimitOrder(
                        COUNTERPARTY,
                        sellSaltThree,
                        APPLES,
                        HBAR,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(6),
                        quantity(1)),
                lv.settleFills(
                        APPLES,
                        HBAR,
                        FEES,
                        takingSeller(COUNTERPARTY, quantity(1), averagePrice(6), sellSaltThree),
                        makingBuyer(MARKET_MAKER, quantity(1), price(6), makerSalt)),
                // Assert fees were only deducted from the final credit
                getAccountBalance(MARKET_MAKER.name())
                        .hasTokenBalance(
                                APPLES.name(),
                                tokenChangeFromSnapshot(
                                        APPLES.name(),
                                        "BEFORE",
                                        BigDecimal.valueOf(1.5 + (1.0 - (1.0 * MAKER_BPS / 10_000)))
                                                .movePointRight(APPLES_DECIMALS)
                                                .longValueExact())));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchOrderHbarHtsFullFullPartialFillsWithFeesOnMakerSpreadCross() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var makerSellSaltThree = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                // Grid of sells, 1 at $1, 2 at $2, and 3 at $3
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(3.00),
                        quantity(3)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(2)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltThree,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.00),
                        quantity(1)),
                // Buy five HBAR for $2.20 per
                lv.placeLimitOrder(
                        PARTY, buySalt, HBAR, USDC, Side.BUY, distantExpiry(), TAKER_BPS, price(2.20), quantity(5)),
                // If we let maker hook fill greedily starting at $3, then not enough
                // in token is left to satisfy the remaining sell orders in the batch
                lv.settleFills(
                                HBAR,
                                USDC,
                                FEES,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(2.20),
                                        makerSellSaltOne,
                                        makerSellSaltTwo,
                                        makerSellSaltThree),
                                takingBuyer(PARTY, quantity(5), price(2.20), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                lv.settleFills(
                        HBAR,
                        USDC,
                        FEES,
                        makingSeller(
                                MARKET_MAKER,
                                quantity(5),
                                averagePrice(2.20),
                                makerSellSaltThree,
                                makerSellSaltTwo,
                                makerSellSaltOne),
                        takingBuyer(PARTY, quantity(5), price(2.20), buySalt)),
                // Three salts should be used up
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltThree),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltTwo),
                lv.assertNoSuchOrder(PARTY.name(), buySalt),
                // And one last HBAR should be left in the lowest-risk ask level
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerSellSaltOne,
                        bd -> assertEquals(
                                0,
                                notional(1.0).compareTo(bd),
                                "Wrong SELL out token amount after batch fill: expected one HBAR, got " + bd)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsFullFillOnMakerSpreadCrossNoFees() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        final var makerOrdersBefore = new AtomicLong();
        final var partyOrdersBefore = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerEvmHookSlotUsage(MARKET_MAKER.name(), makerOrdersBefore::set),
                recordCurrentOwnerEvmHookSlotUsage(PARTY.name(), partyOrdersBefore::set),
                // Market maker places a limit order to sell up to 3 apples for $1.99 each, no fee tolerance
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(1.99),
                        quantity(3)),
                // Second market maker places order to buy 3 apples for $2.00 each, no fee tolerance
                lv.placeLimitOrder(
                        PARTY, buySalt, APPLES, USDC, Side.BUY, distantExpiry(), ZERO_BPS, price(1.99), quantity(3)),
                // Do a zero-fee settlement
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(3), price(1.99), sellSalt),
                        takingBuyer(PARTY, quantity(3), averagePrice(1.99), buySalt)),
                // Both party "out token" limits were fully executed, so hook removes the orders
                assertOwnerHasEvmHookSlotUsageChange(MARKET_MAKER.name(), makerOrdersBefore, 0),
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), partyOrdersBefore, 0));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsFullFillOnMakerSpreadCrossWithFees() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        final var makerOrdersBefore = new AtomicLong();
        final var partyOrdersBefore = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerEvmHookSlotUsage(MARKET_MAKER.name(), makerOrdersBefore::set),
                recordCurrentOwnerEvmHookSlotUsage(PARTY.name(), partyOrdersBefore::set),
                // Market maker places a limit order w/ maker fee tolerance of 12 bps (0.12%)
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(3)),
                // Second party places matching limit order w/ taker fee tolerance of 25 bps (0.25%)
                lv.placeLimitOrder(
                        PARTY, buySalt, APPLES, USDC, Side.BUY, distantExpiry(), TAKER_BPS, price(2.00), quantity(3)),
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingSeller(MARKET_MAKER, quantity(3), price(2.00), sellSalt),
                                takingBuyer(PARTY, quantity(3), averagePrice(2.00), buySalt))
                        .via("fill"),
                // Both party "out token" limits were fully executed, so hook removes the orders
                assertOwnerHasEvmHookSlotUsageChange(MARKET_MAKER.name(), makerOrdersBefore, 0),
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), partyOrdersBefore, 0),
                // Double-check fee transfers once
                getTxnRecord("fill")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(
                                                APPLES.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(3), APPLES_DECIMALS) * TAKER_BPS / 10_000)
                                        .including(
                                                USDC.name(),
                                                FEE_COLLECTOR.name(),
                                                inBaseUnits(quantity(6.00), USDC_DECIMALS) * MAKER_BPS / 10_000))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> htsHtsPartialFillOnMakerSpreadCrossNoFeesThenFees() {
        final var makerSellSalt = randomB64Salt();
        final var counterpartySellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        final var makerOrdersBefore = new AtomicLong();
        final var partyOrdersBefore = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerEvmHookSlotUsage(MARKET_MAKER.name(), makerOrdersBefore::set),
                recordCurrentOwnerEvmHookSlotUsage(PARTY.name(), partyOrdersBefore::set),
                // Max out token - 3 apples
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(3)),
                // Max out token - $12
                lv.placeLimitOrder(
                        PARTY, buySalt, APPLES, USDC, Side.BUY, distantExpiry(), MAKER_BPS, price(6.00), quantity(2)),
                lv.assertOrderAmount(
                        PARTY.name(),
                        buySalt,
                        bd -> assertEquals(
                                0,
                                notional(12.0).compareTo(bd),
                                "Wrong BUY out token amount before partial fill: expected $12.00, got " + bd)),
                lv.settleFillsNoFees(
                        APPLES,
                        USDC,
                        makingSeller(MARKET_MAKER, quantity(3), price(2.00), makerSellSalt),
                        takingBuyer(PARTY, quantity(3), averagePrice(2.00), buySalt)),
                assertOwnerHasEvmHookSlotUsageChange(MARKET_MAKER.name(), makerOrdersBefore, 0),
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), partyOrdersBefore, 1),
                // Amount should reduce by executed out amount ($6)
                lv.assertOrderAmount(
                        PARTY.name(),
                        buySalt,
                        bd -> assertEquals(
                                0,
                                notional(6.0).compareTo(bd),
                                "Wrong BUY out token amount after partial fill: expected $6.00, got " + bd)),
                // Now settle some more using a different counterparty and fees
                lv.placeLimitOrder(
                        COUNTERPARTY,
                        counterpartySellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        TAKER_BPS,
                        price(3.00),
                        quantity(1)),
                lv.settleFills(
                        APPLES,
                        USDC,
                        FEES,
                        takingSeller(COUNTERPARTY, quantity(1), averagePrice(3.00), counterpartySellSalt),
                        makingBuyer(PARTY, quantity(1), price(3.00), buySalt)),
                // Amount should again reduce by executed out amount ($3)
                lv.assertOrderAmount(
                        PARTY.name(),
                        buySalt,
                        bd -> assertEquals(
                                0,
                                notional(3.0).compareTo(bd),
                                "Wrong BUY out token amount after second partial fill: expected $3.00, got " + bd)),
                lv.assertNoSuchOrder(COUNTERPARTY.name(), counterpartySellSalt));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchOrderHtsHtsFullFullPartialFillsWithFeesOnMakerSpreadCross() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var makerSellSaltThree = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                // Grid of sells, 1 at $1, 2 at $2, and 3 at $3
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(3.00),
                        quantity(3)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(2.00),
                        quantity(2)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltThree,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.00),
                        quantity(1)),
                // Buy five of them for $2.20 per
                lv.placeLimitOrder(
                        PARTY, buySalt, APPLES, USDC, Side.BUY, distantExpiry(), TAKER_BPS, price(2.20), quantity(5)),
                // If we let maker hook fill greedily starting at $3, then not enough
                // in token is left to satisfy the remaining sell orders in the batch
                lv.settleFills(
                                APPLES,
                                USDC,
                                FEES,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(2.20),
                                        makerSellSaltOne,
                                        makerSellSaltTwo,
                                        makerSellSaltThree),
                                takingBuyer(PARTY, quantity(5), price(2.20), buySalt))
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // Also fail on extra HBAR transfers
                sourcingContextual(spec -> lv.settleBodyTransformedFillsNoFees(
                                APPLES,
                                USDC,
                                b -> b.setTransfers(TransferList.newBuilder()
                                        .addAllAccountAmounts(List.of(
                                                AccountAmount.newBuilder()
                                                        .setAmount(-1)
                                                        .setAccountID(
                                                                spec.registry().getAccountID(MARKET_MAKER.name()))
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .setAmount(1)
                                                        .setAccountID(
                                                                spec.registry().getAccountID(PARTY.name()))
                                                        .build()))
                                        .build()),
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(2.20),
                                        makerSellSaltThree,
                                        makerSellSaltTwo,
                                        makerSellSaltOne),
                                takingBuyer(PARTY, quantity(5), price(2.20), buySalt))
                        .signedBy(DEFAULT_PAYER, MARKET_MAKER.name(), PARTY.name())
                        .via("extraHbarTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)),
                // revert('hbar neither base nor quoted');
                assertFirstError("extraHbarTx", "hbar neither base nor quoted"),
                // Now do the real fill
                lv.settleFills(
                        APPLES,
                        USDC,
                        FEES,
                        makingSeller(
                                MARKET_MAKER,
                                quantity(5),
                                averagePrice(2.20),
                                makerSellSaltThree,
                                makerSellSaltTwo,
                                makerSellSaltOne),
                        takingBuyer(PARTY, quantity(5), price(2.20), buySalt)),
                // Three salts should be used up
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltThree),
                lv.assertNoSuchOrder(MARKET_MAKER.name(), makerSellSaltTwo),
                lv.assertNoSuchOrder(PARTY.name(), buySalt),
                // And one last apple should be left in the lowest-risk ask level
                lv.assertOrderAmount(
                        MARKET_MAKER.name(),
                        makerSellSaltOne,
                        bd -> assertEquals(
                                0,
                                notional(1.0).compareTo(bd),
                                "Wrong SELL out token amount after batch fill: expected one apple, got " + bd)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchInvocationRejectsMixedInputTokens() {
        final var makerBuyApplesSalt = randomB64Salt();
        final var makerBuyBananasSalt = randomB64Salt();
        final var partySellApplesSalt = randomB64Salt();
        return hapiTest(
                // Successful APPLES/USDC path baseline order.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuyApplesSalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                // Insert BANANAS/USDC into the same owner batch. For BUY orders this changes inToken (APPLES ->
                // BANANAS)
                // while keeping outToken (USDC) unchanged.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerBuyBananasSalt,
                        BANANAS,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                lv.placeLimitOrder(
                        PARTY,
                        partySellApplesSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                lv.settleFillsNoFees(
                                APPLES,
                                USDC,
                                makingBuyer(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(2.00),
                                        makerBuyApplesSalt,
                                        makerBuyBananasSalt),
                                takingSeller(PARTY, quantity(5), averagePrice(2.00), partySellApplesSalt))
                        .via("mixedInTokenTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(inTok  == st.inToken,  "mixed inToken");
                assertSecondError("mixedInTokenTx", "mixed inToken"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchInvocationRejectsMixedOutputTokens() {
        final var makerSellApplesSalt = randomB64Salt();
        final var makerSellBananasSalt = randomB64Salt();
        final var partyBuyApplesSalt = randomB64Salt();
        return hapiTest(
                // Successful APPLES/USDC path baseline order.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellApplesSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                // Insert BANANAS/USDC into the same owner batch. For SELL orders this changes outToken (APPLES ->
                // BANANAS)
                // while keeping inToken (USDC) unchanged.
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellBananasSalt,
                        BANANAS,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuyApplesSalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(5)),
                lv.settleFillsNoFees(
                                APPLES,
                                USDC,
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(5),
                                        averagePrice(2.00),
                                        makerSellApplesSalt,
                                        makerSellBananasSalt),
                                takingBuyer(PARTY, quantity(5), averagePrice(2.00), partyBuyApplesSalt))
                        .via("mixedOutTokenTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(outTok == st.outToken, "mixed outToken");
                assertFirstError("mixedOutTokenTx", "mixed outToken"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> batchInvocationRejectsDataLengthNotMultipleOf32() {
        final var makerSellSaltOne = randomB64Salt();
        final var makerSellSaltTwo = randomB64Salt();
        final var partyBuySalt = randomB64Salt();
        return hapiTest(
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltOne,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(2)),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        makerSellSaltTwo,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(2)),
                lv.placeLimitOrder(
                        PARTY,
                        partyBuySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        ZERO_BPS,
                        price(2.00),
                        quantity(4)),
                // Make each hook payload non-32-byte-aligned (e.g. 64 -> 65 for maker; 32 -> 33 for taker).
                lv.settleDataTransformedFillsNoFees(
                                APPLES,
                                USDC,
                                data -> data.append(Bytes.wrap(new byte[] {(byte) 0xff})),
                                makingSeller(
                                        MARKET_MAKER,
                                        quantity(4),
                                        averagePrice(2.00),
                                        makerSellSaltOne,
                                        makerSellSaltTwo),
                                takingBuyer(PARTY, quantity(4), averagePrice(2.00), partyBuySalt))
                        .via("misalignedDataTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // _loadBytes32(): require(data.length >= offset + 32, "bytes32 oob")
                assertFirstError("misalignedDataTx", "bytes32 oob"));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> singleOrderSanityChecksAreEnforced() {
        final var buySalt = randomB64Salt();
        final var expiredSellSalt = randomB64Salt();
        final var invalidPathSellSalt = randomB64Salt();
        final var missingDetailsSellSalt = randomB64Salt();
        final var invalidOrderTypeSellSalt = randomB64Salt();
        final var validSellSalt = randomB64Salt();
        return hapiTest(
                lv.placeMarketOrder(
                        COUNTERPARTY,
                        buySalt,
                        HBAR,
                        USDC,
                        Side.BUY,
                        distantExpiry(),
                        TAKER_BPS,
                        price(1.00),
                        quantity(100),
                        99,
                        TimeInForce.IOC),
                sourcingContextual(spec -> lv.placeExpiryTransformedLimitOrder(
                        MARKET_MAKER,
                        expiredSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.12),
                        quantity(10),
                        // Negate the expiry time
                        s -> spec.consensusTime().getEpochSecond() - 60)),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.12), expiredSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(10), price(0.12), buySalt))
                        .via("expiredTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(block.timestamp < p.expiration(), "expired")
                assertFirstError("expiredTx", "expired"),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        invalidPathSellSalt,
                        HBAR,
                        HBAR,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.12),
                        quantity(10)),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.12), invalidPathSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(10), price(0.12), buySalt))
                        .via("invalidPathTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(inTok != outTok, "invalid path")
                assertFirstError("invalidPathTx", "invalid path"),
                lv.placeDetailTransformedLimitOrder(
                        MARKET_MAKER,
                        missingDetailsSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.12),
                        quantity(10),
                        details -> Bytes.EMPTY),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.12), missingDetailsSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(10), price(0.12), buySalt))
                        .via("missingDetailsTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(dBytes != bytes32(0), "detail missing")
                assertFirstError("missingDetailsTx", "detail missing"),
                lv.placeKeyTransformedLimitOrder(
                        MARKET_MAKER,
                        invalidOrderTypeSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.12),
                        quantity(10),
                        key -> Bytes.wrap(new byte[] {(byte) 0xff}).append(key.slice(1, key.length() - 1))),
                lv.settleFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(10), price(0.12), invalidOrderTypeSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(10), price(0.12), buySalt))
                        .via("invalidOrderTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                // require(OrderType.isLimitStyle(ot) || OrderType.isMarketStyle(ot), "unsupported type");
                assertFirstError("invalidOrderTx", "unsupported type"),
                lv.placeLimitOrder(
                        MARKET_MAKER,
                        validSellSalt,
                        HBAR,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        ZERO_BPS,
                        price(0.12),
                        quantity(10)),
                sourcingContextual(spec -> lv.settleBodyTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                b -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(spec.registry().getTokenID(APPLES.name()))
                                        .addAllTransfers(List.of(
                                                AccountAmount.newBuilder()
                                                        .setAmount(-1)
                                                        .setAccountID(
                                                                spec.registry().getAccountID(MARKET_MAKER.name()))
                                                        .build(),
                                                AccountAmount.newBuilder()
                                                        .setAmount(1)
                                                        .setAccountID(
                                                                spec.registry().getAccountID(COUNTERPARTY.name()))
                                                        .build()))
                                        .build()),
                                makingSeller(MARKET_MAKER, quantity(10), price(0.12), validSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(10), price(0.12), buySalt))
                        .signedBy(DEFAULT_PAYER, MARKET_MAKER.name(), COUNTERPARTY.name())
                        .via("foreignTokenTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)),
                // revert("foreign")
                assertFirstError("foreignTokenTx", "foreign"),
                sourcingContextual(spec -> lv.settleUnmergedFillsNoFees(
                                HBAR,
                                USDC,
                                makingSeller(MARKET_MAKER, quantity(0), price(0.12), validSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(0), price(0.12), buySalt))
                        .via("noFillTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)),
                // revert("IOC: no fill")
                assertSecondError("noFillTx", "IOC: no fill"),
                sourcingContextual(spec -> lv.settleDataTransformedFillsNoFees(
                                HBAR,
                                USDC,
                                data -> Bytes.wrap(new byte[] {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc}),
                                makingSeller(MARKET_MAKER, quantity(1), price(0.12), validSellSalt),
                                takingBuyer(COUNTERPARTY, quantity(1), price(0.12), buySalt))
                        .via("truncatedTx")
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)),
                // require(args.length >= 32, "args too short")
                assertFirstError("truncatedTx", "args too short"));
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
