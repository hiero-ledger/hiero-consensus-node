// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertOwnerHasEvmHookSlotUsageChange;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordCurrentOwnerEvmHookSlotUsage;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.hip1195.LambdaplexVerbs.FillParty.*;
import static com.hedera.services.bdd.suites.hip1195.LambdaplexVerbs.inBaseUnits;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
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
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests exercising the Lambdaplex protocol hook.
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
@HapiTestLifecycle
@OrderedInIsolation
public class LambdaplexTest implements InitcodeTransform {

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

    @Contract(contract = "MockSupraRegistry", creationGas = 1_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(contract = "OrderFlowAllowance", creationGas = 2_000_000L, initcodeTransform = LambdaplexTest.class)
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

    @HapiTest
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

    @HapiTest
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

    @HapiTest
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
                assertOwnerHasEvmHookSlotUsageChange(PARTY.name(), makerOrdersBefore, 1),
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

    private static BigDecimal averagePrice(final double d) {
        return BigDecimal.valueOf(d);
    }

    private static BigDecimal price(final double d) {
        return BigDecimal.valueOf(d);
    }

    private static BigDecimal quantity(final double d) {
        return BigDecimal.valueOf(d);
    }

    private static BigDecimal notional(final double d) {
        return BigDecimal.valueOf(d);
    }

    private static String randomB64Salt() {
        return Base64.getEncoder().encodeToString(TxnUtils.randomUtf8Bytes(7));
    }

    private static Instant iocExpiry() {
        return Instant.now().plus(Duration.ofSeconds(30));
    }

    private static Instant distantExpiry() {
        return Instant.now().plus(Duration.ofDays(30));
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
