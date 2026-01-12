// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static java.math.RoundingMode.HALF_UP;
import static java.util.Objects.requireNonNull;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Hook;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.EvmHookCall;
import com.hederahashgraph.api.proto.java.HookCall;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
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
 */
@HapiTestLifecycle
public class LambdaplexTest implements InitcodeTransform {
    // Order type constants
    private static final byte LIMIT = 0;
    private static final byte MARKET = 1;
    // Stops that trigger on an oracle price less than or equal to the stop
    private static final byte STOP_LIMIT_LT = 2;
    private static final byte STOP_MARKET_LT = 3;
    // Stops that trigger on an oracle price greater than or equal to the stop
    private static final byte STOP_LIMIT_GT = 4;
    private static final byte STOP_MARKET_GT = 5;

    private static final int HOOK_ID = 42;
    private static final int MAKER_BPS = 12;
    private static final int TAKER_BPS = 25;
    private static final long SWAP_GAS_LIMIT = 20_000L;

    private static final String REGISTRY_ADDRESS_TPL = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";

    private enum Side {
        BUY,
        SELL
    }

    private enum OrderType {
        MARKET,
        LIMIT,
        STOP_MARKET,
        STOP_LIMIT
    }

    private enum StopDirection {
        LT,
        GT
    }

    private enum TimeInForce {
        IOC,
        GTC,
        FOK
    }

    private record FillParty(
            @NonNull SpecAccount account,
            @NonNull Side side,
            @NonNull BigDecimal debit,
            @NonNull BigDecimal credit,
            @NonNull String... b64Salts) {
        private FillParty {
            requireNonNull(account);
            requireNonNull(side);
            requireNonNull(debit);
            requireNonNull(credit);
            requireNonNull(b64Salts);
        }

        public static FillParty seller(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Seller debits base, credits quote
            return new FillParty(account, Side.SELL, quantity.negate(), quantity.multiply(averagePrice), b64Salts);
        }

        public static FillParty buyer(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Buyer debits quote, credits base
            return new FillParty(
                    account, Side.BUY, quantity.multiply(averagePrice).negate(), quantity, b64Salts);
        }
    }

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

    private final Map<String, Bytes> saltPrefixes = new HashMap<>();

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

    /**
     * Creates a test that,
     * <ol>
     *   <li>Puts a standing limit SELL order for up to 3 apples at $1.99 each into the order flow; then</li>
     *   <li>Puts a market BUY order for 2.5 apples with no more than 5% slippage from a $2 reference price; then</li>
     *   <li>Submits a CryptoTransfer to settle the implied trade.</li>
     * </ol>
     */
    @HapiTest
    final Stream<DynamicTest> singleFillLimitSellMarketBuy() {
        final var sellSalt = randomB64Salt();
        final var buySalt = randomB64Salt();
        return hapiTest(
                // Market maker places a standing order to sell up to 3 apples for $1.99 each
                placeLimitOrder(
                        MARKET_MAKER,
                        sellSalt,
                        APPLES,
                        USDC,
                        Side.SELL,
                        distantExpiry(),
                        MAKER_BPS,
                        price(1.99),
                        quantity(3)),
                // Party places a market order to buy 2.5 apples with no more than 5% slippage from a $2 reference price
                placeMarketOrder(
                        PARTY,
                        buySalt,
                        APPLES,
                        USDC,
                        Side.BUY,
                        iocExpiry(),
                        TAKER_BPS,
                        price(2.00),
                        quantity(2.5),
                        5,
                        TimeInForce.FOK),
                // This is a match, so settle the implied trade
                settleFills(
                        APPLES,
                        USDC,
                        FillParty.seller(MARKET_MAKER, quantity(2.5), price(1.99), sellSalt),
                        FillParty.buyer(PARTY, quantity(2.5), averagePrice(1.99), buySalt)));
    }

    private HapiCryptoTransfer settleFills(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final FillParty... fillParties) {
        return TxnVerbs.cryptoTransfer((spec, builder) -> {
            final var baseToken = specBaseToken.tokenOrThrow(spec.targetNetworkOrThrow());
            final var quoteToken = specQuoteToken.tokenOrThrow(spec.targetNetworkOrThrow());
            final List<AccountAmount> baseAdjustments = new ArrayList<>();
            final List<AccountAmount> quoteAdjustments = new ArrayList<>();
            for (final var party : fillParties) {
                final var partyId = spec.registry().getAccountID(party.account().name());
                final var prefix = saltPrefixes.get(party.b64Salts()[0]);
                switch (party.side()) {
                    case BUY -> {
                        // Debiting quote token, crediting base token
                        quoteAdjustments.add(AccountAmount.newBuilder()
                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                        .setHookId(HOOK_ID)
                                        .setEvmHookCall(EvmHookCall.newBuilder()
                                                .setGasLimit(SWAP_GAS_LIMIT)
                                                .setData(fromPbj(prefix))))
                                .setAmount(inBaseUnits(party.debit(), quoteToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                        baseAdjustments.add(AccountAmount.newBuilder()
                                .setAmount(inBaseUnits(party.credit(), baseToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                    }
                    case SELL -> {
                        // Debiting base token, crediting quote token
                        baseAdjustments.add(AccountAmount.newBuilder()
                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                        .setHookId(HOOK_ID)
                                        .setEvmHookCall(EvmHookCall.newBuilder()
                                                .setGasLimit(SWAP_GAS_LIMIT)
                                                .setData(fromPbj(prefix))))
                                .setAmount(inBaseUnits(party.debit(), baseToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                        quoteAdjustments.add(AccountAmount.newBuilder()
                                .setAmount(inBaseUnits(party.credit(), quoteToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                    }
                }
            }
            final var registry = spec.registry();
            builder.addTokenTransfers(TokenTransferList.newBuilder()
                            .setToken(registry.getTokenID(specBaseToken.name()))
                            .addAllTransfers(baseAdjustments))
                    .addTokenTransfers(TokenTransferList.newBuilder()
                            .setToken(registry.getTokenID(specQuoteToken.name()))
                            .addAllTransfers(quoteAdjustments))
                    .build();
        });
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

    private static String randomB64Salt() {
        return Base64.getEncoder().encodeToString(TxnUtils.randomUtf8Bytes(7));
    }

    private static Instant iocExpiry() {
        return Instant.now().plus(Duration.ofSeconds(30));
    }

    private static Instant distantExpiry() {
        return Instant.now().plus(Duration.ofDays(30));
    }

    private SpecOperation placeLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity) {
        return placeOrderInternal(
                account,
                b64Salt,
                specBaseToken,
                specQuoteToken,
                side,
                OrderType.LIMIT,
                null,
                expiry,
                price,
                quantity,
                feeBps,
                0,
                0);
    }

    private SpecOperation placeStopLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal triggerPrice,
            @NonNull final StopDirection stopDirection,
            @NonNull final BigDecimal quantity) {
        return placeOrderInternal(
                account,
                b64Salt,
                specBaseToken,
                specQuoteToken,
                side,
                OrderType.STOP_LIMIT,
                stopDirection,
                expiry,
                price,
                quantity,
                feeBps,
                // We reuse priceDeviationCentiBps to encode the trigger price as a percentage of the price
                triggerPrice
                        .multiply(BigDecimal.valueOf(100_00))
                        .divide(price, HALF_UP)
                        .intValue(),
                0);
    }

    private SpecOperation placeMarketOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal referencePrice,
            @NonNull final BigDecimal quantity,
            final int slippagePercentTolerance,
            TimeInForce timeInForce) {
        return placeOrderInternal(
                account,
                b64Salt,
                specBaseToken,
                specQuoteToken,
                side,
                OrderType.MARKET,
                null,
                expiry,
                referencePrice,
                quantity,
                feeBps,
                slippagePercentTolerance * 10_000,
                // "Fill-or-kill" is encoded as a minimum fill percentage of 100% minus the slippage tolerance
                timeInForce == TimeInForce.FOK ? 1_000_000 * (100 - slippagePercentTolerance) / 100 : 0);
    }

    private SpecOperation placeStopMarketOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal stopPrice,
            @NonNull final StopDirection stopDirection,
            @NonNull final BigDecimal quantity,
            final int slippagePercentTolerance,
            boolean fillOrKill) {
        return placeOrderInternal(
                account,
                b64Salt,
                specBaseToken,
                specQuoteToken,
                side,
                OrderType.STOP_MARKET,
                stopDirection,
                expiry,
                stopPrice,
                quantity,
                feeBps,
                slippagePercentTolerance * 10_000,
                fillOrKill ? 1_000_000 : 0);
    }

    private SpecOperation placeOrderInternal(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final OrderType orderType,
            @Nullable final StopDirection stopDirection,
            @NonNull final Instant expiry,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity,
            final int feeCentiBps,
            final int priceDeviationCentiBps,
            final int minFillDeciBps) {
        return sourcingContextual(spec -> {
            final var targetNetwork = spec.targetNetworkOrThrow();
            final var baseToken = specBaseToken.tokenOrThrow(targetNetwork);
            final var quoteToken = specQuoteToken.tokenOrThrow(targetNetwork);
            final var baseAmount = toBigInteger(quantity, baseToken.decimals());
            final var quotePrice = Fraction.from(price, baseToken.decimals(), quoteToken.decimals());
            final Token inputToken;
            final Token outputToken;
            final Bytes detailValue;
            if (side == Side.BUY) {
                // User is debited quote token
                outputToken = quoteToken;
                // And credited base token
                inputToken = baseToken;
                detailValue = encodeOrderDetailValue(
                        // So storage mapping entry amount is in quote token units
                        mulDiv(baseAmount, quotePrice.numerator(), quotePrice.denominator()),
                        // With a price in base/quote terms
                        quotePrice.biDenominator(),
                        quotePrice.biNumerator(),
                        BigInteger.valueOf(priceDeviationCentiBps),
                        BigInteger.valueOf(minFillDeciBps));
            } else {
                // User is debited base token
                outputToken = baseToken;
                // And credited quote token
                inputToken = quoteToken;
                detailValue = encodeOrderDetailValue(
                        // So storage mapping entry amount is in base token units
                        baseAmount,
                        // With a price in quote/base terms
                        quotePrice.biNumerator(),
                        quotePrice.biDenominator(),
                        BigInteger.valueOf(priceDeviationCentiBps),
                        BigInteger.valueOf(minFillDeciBps));
            }
            final var prefixKey = encodeOrderPrefixKey(
                    orderType,
                    stopDirection,
                    inputToken.tokenIdOrThrow().tokenNum(),
                    outputToken.tokenIdOrThrow().tokenNum(),
                    expiry.getEpochSecond(),
                    feeCentiBps,
                    b64Salt);
            saltPrefixes.put(b64Salt, prefixKey);
            return accountEvmHookStore(account.name(), HOOK_ID)
                    .putMappingEntryWithKey(Bytes.EMPTY, prefixKey, minimalKey(detailValue));
        });
    }

    private Bytes encodeOrderPrefixKey(
            @NonNull final OrderType type,
            @Nullable final StopDirection stopDirection,
            final long inputTokenId,
            final long outputTokenId,
            final long expiry,
            final int feeBps,
            @NonNull final String b64Salt) {
        final byte[] prefix = new byte[32];
        prefix[0] = asPrefixType(type, stopDirection);
        int cursor = 2;
        System.arraycopy(Longs.toByteArray(outputTokenId), 0, prefix, cursor, 8);
        cursor += 8;
        System.arraycopy(Longs.toByteArray(inputTokenId), 0, prefix, cursor, 8);
        cursor += 8;
        System.arraycopy(Longs.toByteArray(expiry), 4, prefix, cursor, 4);
        cursor += 4;
        System.arraycopy(Ints.toByteArray(feeBps), 1, prefix, cursor, 3);
        cursor += 3;
        System.arraycopy(Base64.getDecoder().decode(b64Salt), 0, prefix, cursor, 7);
        return Bytes.wrap(prefix);
    }

    private Bytes encodeOrderDetailValue(
            @NonNull final BigInteger quantity,
            @NonNull final BigInteger numerator,
            @NonNull final BigInteger denominator,
            @NonNull final BigInteger deviationBps,
            @NonNull final BigInteger minFillBps) {
        final var encoded = quantity.shiftLeft(193)
                .or(numerator.shiftLeft(130))
                .or(denominator.shiftLeft(67))
                .or(deviationBps.shiftLeft(43))
                .or(minFillBps.shiftLeft(19));
        return Bytes.wrap(requireNonNull(CommonUtils.unhex(String.format("%064x", encoded))));
    }

    private byte asPrefixType(@NonNull final OrderType type, @Nullable final StopDirection stopDirection) {
        return switch (type) {
            case LIMIT -> LIMIT;
            case MARKET -> MARKET;
            case STOP_LIMIT ->
                switch (requireNonNull(stopDirection)) {
                    case LT -> STOP_LIMIT_LT;
                    case GT -> STOP_LIMIT_GT;
                };
            case STOP_MARKET ->
                switch (requireNonNull(stopDirection)) {
                    case LT -> STOP_MARKET_LT;
                    case GT -> STOP_MARKET_GT;
                };
        };
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

    private record Fraction(long numerator, long denominator) {
        /**
         * Converts a decimal price (one whole input token costs {@code price} whole output tokens)
         * into a fraction with both sides denominated in the base units of the respective tokens.
         * @param price the user price in whole tokens
         * @param inputTokenDecimals number of decimals for the input token
         * @param outputTokenDecimals number of decimals for the output token
         * @return the price as a fraction in least terms, denominated in base units
         */
        public static Fraction from(BigDecimal price, int inputTokenDecimals, int outputTokenDecimals) {
            var numerator = toBigInteger(price, outputTokenDecimals);
            var denominator = BigInteger.TEN.pow(inputTokenDecimals);
            final var gcd = numerator.gcd(denominator);
            if (!gcd.equals(BigInteger.ZERO)) {
                numerator = numerator.divide(gcd);
                denominator = denominator.divide(gcd);
            }
            return new Fraction(numerator.longValueExact(), denominator.longValueExact());
        }

        public BigInteger biNumerator() {
            return BigInteger.valueOf(numerator);
        }

        public BigInteger biDenominator() {
            return BigInteger.valueOf(denominator);
        }
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

    private static long inBaseUnits(BigDecimal amount, int decimals) {
        return toBigInteger(amount, decimals).longValueExact();
    }

    private static BigInteger toBigInteger(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, HALF_UP).toBigIntegerExact();
    }

    private static BigInteger mulDiv(BigInteger v, long n, long d) {
        if (d == 0) {
            throw new IllegalArgumentException("Denominator must be non-zero");
        }
        return v.multiply(BigInteger.valueOf(n)).divide(BigInteger.valueOf(d));
    }
}
