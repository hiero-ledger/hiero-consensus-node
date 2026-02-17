// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195.lambdaplex;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.Role.MAKER;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.Role.TAKER;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.Side.BUY;
import static com.hedera.services.bdd.suites.hip1195.lambdaplex.Side.SELL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static java.math.RoundingMode.HALF_UP;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.EvmHookCall;
import com.hederahashgraph.api.proto.java.HookCall;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;
import org.hiero.base.utility.CommonUtils;

public class LambdaplexVerbs {
    // Order type constants
    private static final byte LIMIT = 0;
    private static final byte MARKET = 1;
    // Stops that trigger on an oracle price less than or equal to the stop
    private static final byte STOP_LIMIT_LT = 2;
    private static final byte STOP_MARKET_LT = 3;
    // Stops that trigger on an oracle price greater than or equal to the stop
    private static final byte STOP_LIMIT_GT = 4;
    private static final byte STOP_MARKET_GT = 5;

    private static final long BASE_GAS_LIMIT = 8_000L;
    private static final long DIRECT_PREFIX_GAS_INCREMENT = 20_000L;
    private static final long ORACLE_SETUP_GAS = 300_000L;
    public static final long ORACLE_HOOK_GAS = 300_000L;
    private static final int ONE_HUNDRED_PERCENT_CENTI_BPS = 1_000_000;
    private static final Bytes PADDED_ZERO = leftPad32(Bytes.EMPTY);

    // Sentinel for HBAR
    public static final SpecFungibleToken HBAR = new SpecFungibleToken("<HBAR>") {
        private static final Token HBAR_TOKEN = Token.newBuilder()
                .tokenId(TokenID.DEFAULT)
                .name("Hedera")
                .symbol("HBAR")
                .decimals(8)
                .build();

        @Override
        public Token tokenOrThrow(@NonNull final HederaNetwork network) {
            requireNonNull(network);
            return HBAR_TOKEN;
        }
    };

    private final long hookId;
    private final String feeCollectorName;
    private final Map<String, Bytes> saltPrefixes = new HashMap<>();
    private final Map<String, Integer> saltQuantityDecimals = new HashMap<>();

    public LambdaplexVerbs(final long hookId, String feeCollectorName) {
        this.hookId = hookId;
        this.feeCollectorName = feeCollectorName;
    }

    public static BigInteger toBigInteger(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, HALF_UP).toBigIntegerExact();
    }

    public static long inBaseUnits(BigDecimal amount, int decimals) {
        return toBigInteger(amount, decimals).longValueExact();
    }

    public static HapiGetTxnRecord assertFirstError(@NonNull final String tx, @NonNull final String message) {
        return getTxnRecord(tx)
                .andAllChildRecords()
                .hasChildRecords(recordWith().contractCallResult(resultWith().error(asErrorReason(message))));
    }

    public static HapiGetTxnRecord assertSecondError(@NonNull final String tx, @NonNull final String message) {
        return getTxnRecord(tx)
                .andAllChildRecords()
                .hasChildRecords(
                        recordWith().status(REVERTED_SUCCESS),
                        recordWith().contractCallResult(resultWith().error(asErrorReason(message))));
    }

    public static BigDecimal averagePrice(final double d) {
        return BigDecimal.valueOf(d);
    }

    public static BigDecimal price(final double d) {
        return BigDecimal.valueOf(d);
    }

    public static BigDecimal quantity(final double d) {
        return BigDecimal.valueOf(d);
    }

    public static BigDecimal notional(final double d) {
        return BigDecimal.valueOf(d);
    }

    public static String randomB64Salt() {
        return Base64.getEncoder().encodeToString(TxnUtils.randomUtf8Bytes(7));
    }

    public static Instant iocExpiry() {
        return Instant.now().plus(Duration.ofSeconds(30));
    }

    public static Instant distantExpiry() {
        return Instant.now().plus(Duration.ofDays(30));
    }

    public static String asErrorReason(@NonNull final String message) {
        // 4-byte selector: keccak256("Error(string)")[:4]
        final var selector = new byte[] {0x08, (byte) 0xc3, 0x79, (byte) 0xa0};
        final var msgBytes = message.getBytes(StandardCharsets.UTF_8);
        final int paddedLen = ((msgBytes.length + 31) / 32) * 32;
        // selector (4) + offset (32) + length (32) + padded content
        final var result = new byte[4 + 32 + 32 + paddedLen];
        System.arraycopy(selector, 0, result, 0, 4);
        // offset = 0x20
        result[4 + 31] = 0x20;
        // string length
        result[4 + 32 + 31] = (byte) msgBytes.length;
        // string content (already zero-padded by default)
        System.arraycopy(msgBytes, 0, result, 4 + 64, msgBytes.length);
        final var sb = new StringBuilder("0x");
        for (byte b : result) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public SpecOperation assertNoSuchOrder(@NonNull final SpecAccount account, @NonNull final String b64Salt) {
        return assertNoSuchOrder(account.name(), b64Salt);
    }

    public SpecOperation assertNoSuchOrder(@NonNull final String accountName, @NonNull final String b64Salt) {
        requireNonNull(accountName);
        requireNonNull(b64Salt);
        return doingContextual(spec -> {
            final var pair = matchingOrder(spec, accountName, b64Salt);
            assertNull(pair, "Expected no matching order for account " + accountName + " and salt " + b64Salt);
        });
    }

    public SpecOperation assertOrderAmount(
            @NonNull final SpecAccount account, @NonNull final String b64Salt, @NonNull final Consumer<BigDecimal> cb) {
        return assertOrderAmount(account.name(), b64Salt, cb);
    }

    public SpecOperation assertOrderAmount(
            @NonNull final String accountName, @NonNull final String b64Salt, @NonNull final Consumer<BigDecimal> cb) {
        requireNonNull(accountName);
        requireNonNull(b64Salt);
        requireNonNull(cb);
        return doingContextual(spec -> {
            final var pair = matchingOrder(spec, accountName, b64Salt);
            assertNotNull(pair, "Could not find matching order for account " + accountName + " and salt " + b64Salt);
            final int decimals = requireNonNull(saltQuantityDecimals.get(b64Salt));
            final var quantity = BigDecimal.valueOf(
                            new BigInteger(1, pair.value().toByteArray())
                                    .shiftRight(193)
                                    .longValueExact())
                    .movePointLeft(decimals);
            cb.accept(quantity);
        });
    }

    private @Nullable Pair<Bytes, Bytes> matchingOrder(
            @NonNull final HapiSpec spec, @NonNull final String accountName, @NonNull final String b64Salt) {
        final var state = spec.repeatableEmbeddedHederaOrThrow().state();
        final var accountId = toPbj(spec.registry().getAccountID(accountName));
        final var account = state.getReadableStates(TokenService.NAME)
                .<AccountID, Account>get(ACCOUNTS_STATE_ID)
                .get(accountId);
        if (account == null) {
            return null;
        }
        long n = account.numberEvmHookStorageSlots();
        var nextHookId = account.firstHookId();
        final var hookStates =
                state.getReadableStates(ContractService.NAME).<HookId, EvmHookState>get(EVM_HOOK_STATES_STATE_ID);
        final var hookStorage =
                state.getReadableStates(ContractService.NAME).<EvmHookSlotKey, SlotValue>get(EVM_HOOK_STORAGE_STATE_ID);
        final var searchKey = rawSlotKey(b64Salt);
        while (n > 0) {
            final var hookId = HookId.newBuilder()
                    .entityId(HookEntityId.newBuilder().accountId(accountId))
                    .hookId(nextHookId)
                    .build();
            final var hookState = hookStates.get(hookId);
            int m = requireNonNull(hookState).numStorageSlots();
            var nextSlotKey = hookState.firstContractStorageKey();
            while (m > 0) {
                final var key = new EvmHookSlotKey(hookId, nextSlotKey);
                final var slotValue = requireNonNull(hookStorage.get(key));
                if (key.key().equals(searchKey)) {
                    return new Pair<>(key.key(), slotValue.value());
                }
                nextSlotKey = slotValue.nextKey();
                m--;
            }
            n--;
        }
        return null;
    }

    public record FillParty(
            @NonNull SpecAccount account,
            @NonNull Role role,
            @NonNull Side side,
            @NonNull BigDecimal debit,
            @NonNull BigDecimal credit,
            long gasLimit,
            @NonNull String... b64Salts) {
        public FillParty {
            requireNonNull(account);
            requireNonNull(role);
            requireNonNull(side);
            requireNonNull(debit);
            requireNonNull(credit);
            requireNonNull(b64Salts);
        }

        public Bytes toDirectPrefixes(Function<String, Bytes> prefixFn) {
            Bytes result = Bytes.EMPTY;
            for (String b64Salt : b64Salts) {
                result = result.append(prefixFn.apply(b64Salt));
            }
            return result;
        }

        public FillParty withGasLimit(final long updatedGasLimit) {
            return new FillParty(account, role, side, debit, credit, updatedGasLimit, b64Salts);
        }

        public record FillLeg(
                @NonNull BigDecimal quantity,
                @NonNull BigDecimal averagePrice,
                @NonNull String b64Salt) {
            public FillLeg {
                requireNonNull(quantity);
                requireNonNull(averagePrice);
                requireNonNull(b64Salt);
            }
        }

        public static FillLeg leg(
                @NonNull final BigDecimal quantity,
                @NonNull final BigDecimal averagePrice,
                @NonNull final String b64Salt) {
            return new FillLeg(quantity, averagePrice, b64Salt);
        }

        public static FillParty makingSeller(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            return fromQuantityAndAveragePrice(account, MAKER, SELL, quantity, averagePrice, b64Salts);
        }

        public static FillParty makingSeller(final SpecAccount account, final FillLeg... legs) {
            return fromLegs(account, MAKER, SELL, legs);
        }

        public static FillParty takingSeller(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            return fromQuantityAndAveragePrice(account, TAKER, SELL, quantity, averagePrice, b64Salts);
        }

        public static FillParty takingSeller(final SpecAccount account, final FillLeg... legs) {
            return fromLegs(account, TAKER, SELL, legs);
        }

        public static FillParty takingBuyer(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            return fromQuantityAndAveragePrice(account, TAKER, BUY, quantity, averagePrice, b64Salts);
        }

        public static FillParty takingBuyer(final SpecAccount account, final FillLeg... legs) {
            return fromLegs(account, TAKER, BUY, legs);
        }

        public static FillParty makingBuyer(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            return fromQuantityAndAveragePrice(account, MAKER, BUY, quantity, averagePrice, b64Salts);
        }

        public static FillParty makingBuyer(final SpecAccount account, final FillLeg... legs) {
            return fromLegs(account, MAKER, BUY, legs);
        }

        private static FillParty fromLegs(
                @NonNull final SpecAccount account,
                @NonNull final Role role,
                @NonNull final Side side,
                @NonNull final FillLeg... legs) {
            requireNonNull(legs);
            if (legs.length == 0) {
                throw new IllegalArgumentException("At least one leg is required");
            }
            var totalQuantity = BigDecimal.ZERO;
            var totalNotional = BigDecimal.ZERO;
            final var salts = new String[legs.length];
            for (int i = 0; i < legs.length; i++) {
                final var leg = requireNonNull(legs[i]);
                totalQuantity = totalQuantity.add(leg.quantity());
                totalNotional = totalNotional.add(leg.quantity().multiply(leg.averagePrice()));
                salts[i] = leg.b64Salt();
            }
            return fromQuantityAndNotional(account, role, side, totalQuantity, totalNotional, salts);
        }

        private static FillParty fromQuantityAndAveragePrice(
                @NonNull final SpecAccount account,
                @NonNull final Role role,
                @NonNull final Side side,
                @NonNull final BigDecimal quantity,
                @NonNull final BigDecimal averagePrice,
                @NonNull final String... b64Salts) {
            requireNonNull(quantity);
            requireNonNull(averagePrice);
            return fromQuantityAndNotional(account, role, side, quantity, quantity.multiply(averagePrice), b64Salts);
        }

        private static FillParty fromQuantityAndNotional(
                @NonNull final SpecAccount account,
                @NonNull final Role role,
                @NonNull final Side side,
                @NonNull final BigDecimal quantity,
                @NonNull final BigDecimal notional,
                @NonNull final String... b64Salts) {
            requireNonNull(account);
            requireNonNull(role);
            requireNonNull(side);
            requireNonNull(quantity);
            requireNonNull(notional);
            requireNonNull(b64Salts);
            if (side == SELL) {
                // Seller debits base, credits quote.
                return new FillParty(
                        account,
                        role,
                        side,
                        quantity.negate(),
                        notional,
                        BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                        b64Salts);
            } else {
                // Buyer debits quote, credits base.
                return new FillParty(
                        account,
                        role,
                        side,
                        notional.negate(),
                        quantity,
                        BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                        b64Salts);
            }
        }
    }

    private Bytes rawSlotKey(@NonNull final String b64Salt) {
        final var prefix = requireNonNull(saltPrefixes.get(b64Salt));
        return slotKeyOfMappingEntry(
                PADDED_ZERO, EvmHookMappingEntry.newBuilder().key(prefix).build());
    }

    public SpecOperation placeLimitOrder(
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
                feeBps * 100,
                0,
                0,
                null,
                null,
                null);
    }

    public SpecOperation placeExpiryTransformedLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity,
            @Nullable final LongUnaryOperator expiryTransform) {
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
                feeBps * 100,
                0,
                0,
                expiryTransform,
                null,
                null);
    }

    public SpecOperation placeDetailTransformedLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity,
            @Nullable final UnaryOperator<Bytes> detailsTransform) {
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
                feeBps * 100,
                0,
                0,
                null,
                detailsTransform,
                null);
    }

    public SpecOperation placeKeyTransformedLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final String b64Salt,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final Instant expiry,
            final int feeBps,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity,
            @Nullable final UnaryOperator<Bytes> keyTransform) {
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
                feeBps * 100,
                0,
                0,
                null,
                null,
                keyTransform);
    }

    public SpecOperation placeStopLimitOrder(
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
                feeBps * 100,
                // We reuse priceDeviationCentiBps to encode the trigger price as a percentage of the price
                triggerPrice
                        .multiply(BigDecimal.valueOf(ONE_HUNDRED_PERCENT_CENTI_BPS))
                        .divide(price, HALF_UP)
                        .intValue(),
                0,
                null,
                null,
                null);
    }

    public SpecOperation placeMarketOrder(
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
                feeBps * 100,
                slippagePercentTolerance * 10_000,
                // "Fill-or-kill" is encoded as a minimum fill percentage of 100% minus the slippage tolerance
                timeInForce == TimeInForce.FOK ? 1_000_000 * (100 - slippagePercentTolerance) / 100 : 0,
                null,
                null,
                null);
    }

    public SpecOperation placeStopMarketOrder(
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
                feeBps * 100,
                slippagePercentTolerance * 10_000,
                fillOrKill ? 1_000_000 : 0,
                null,
                null,
                null);
    }

    public HapiCryptoTransfer settleFills(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Fees fees,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, fees, null, null, false, fillParties);
    }

    public HapiCryptoTransfer settleFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, null, null, false, fillParties);
    }

    public HapiCryptoTransfer settleUnmergedFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, null, null, true, fillParties);
    }

    public HapiCryptoTransfer settleBodyTransformedFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Consumer<CryptoTransferTransactionBody.Builder> bodySpec,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, bodySpec, null, false, fillParties);
    }

    public HapiCryptoTransfer settleDataTransformedFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final UnaryOperator<Bytes> dataTransform,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, null, dataTransform, false, fillParties);
    }

    public HapiCryptoTransfer settleDataTransformedFills(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Fees fees,
            @NonNull final UnaryOperator<Bytes> dataTransform,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, fees, null, dataTransform, false, fillParties);
    }

    private HapiCryptoTransfer settleFillsInternal(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @Nullable final Fees fees,
            @Nullable final Consumer<CryptoTransferTransactionBody.Builder> bodySpec,
            @Nullable final UnaryOperator<Bytes> dataTransform,
            final boolean skipAccountAmountsMerge,
            @NonNull final FillParty... fillParties) {
        return TxnVerbs.cryptoTransfer((spec, builder) -> {
            final var baseToken = specBaseToken.tokenOrThrow(spec.targetNetworkOrThrow());
            final var quoteToken = specQuoteToken.tokenOrThrow(spec.targetNetworkOrThrow());
            final List<AccountAmount> baseAdjustments = new ArrayList<>();
            final List<AccountAmount> quoteAdjustments = new ArrayList<>();
            for (final var party : fillParties) {
                final var partyId = spec.registry().getAccountID(party.account().name());
                var data = party.toDirectPrefixes(saltPrefixes::get);
                if (dataTransform != null) {
                    data = dataTransform.apply(data);
                }
                switch (party.side()) {
                    case BUY -> {
                        // Debiting quote token, crediting base token
                        quoteAdjustments.add(AccountAmount.newBuilder()
                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                        .setHookId(hookId)
                                        .setEvmHookCall(EvmHookCall.newBuilder()
                                                .setGasLimit(party.gasLimit())
                                                .setData(fromPbj(data))))
                                .setAmount(inBaseUnits(party.debit(), quoteToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                        long credit = inBaseUnits(party.credit(), baseToken.decimals());
                        if (fees != null) {
                            final long fee = credit * fees.forRole(party.role()) / 10_000;
                            credit -= fee;
                            baseAdjustments.add(AccountAmount.newBuilder()
                                    .setAmount(fee)
                                    .setAccountID(spec.registry().getAccountID(feeCollectorName))
                                    .build());
                        }
                        baseAdjustments.add(AccountAmount.newBuilder()
                                .setAmount(credit)
                                .setAccountID(partyId)
                                .build());
                    }
                    case SELL -> {
                        // Debiting base token, crediting quote token
                        baseAdjustments.add(AccountAmount.newBuilder()
                                .setPreTxAllowanceHook(HookCall.newBuilder()
                                        .setHookId(hookId)
                                        .setEvmHookCall(EvmHookCall.newBuilder()
                                                .setGasLimit(party.gasLimit())
                                                .setData(fromPbj(data))))
                                .setAmount(LambdaplexVerbs.inBaseUnits(party.debit(), baseToken.decimals()))
                                .setAccountID(partyId)
                                .build());
                        long credit = LambdaplexVerbs.inBaseUnits(party.credit(), quoteToken.decimals());
                        if (fees != null) {
                            final long fee = credit * fees.forRole(party.role()) / 10_000;
                            credit -= fee;
                            quoteAdjustments.add(AccountAmount.newBuilder()
                                    .setAmount(fee)
                                    .setAccountID(spec.registry().getAccountID(feeCollectorName))
                                    .build());
                        }
                        quoteAdjustments.add(AccountAmount.newBuilder()
                                .setAmount(credit)
                                .setAccountID(partyId)
                                .build());
                    }
                }
            }
            final var mergedBaseAdjustments = skipAccountAmountsMerge ? baseAdjustments : mergedAdjustments(baseAdjustments);
            final var mergedQuoteAdjustments = skipAccountAmountsMerge ? quoteAdjustments : mergedAdjustments(quoteAdjustments);
            final var registry = spec.registry();
            if (specBaseToken == HBAR) {
                builder.setTransfers(TransferList.newBuilder().addAllAccountAmounts(mergedBaseAdjustments))
                        .addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specQuoteToken.name()))
                                .addAllTransfers(mergedQuoteAdjustments))
                        .build();
            } else if (specQuoteToken == HBAR) {
                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specBaseToken.name()))
                                .addAllTransfers(mergedBaseAdjustments))
                        .setTransfers(TransferList.newBuilder().addAllAccountAmounts(mergedQuoteAdjustments))
                        .build();
            } else {
                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specBaseToken.name()))
                                .addAllTransfers(mergedBaseAdjustments))
                        .addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specQuoteToken.name()))
                                .addAllTransfers(mergedQuoteAdjustments))
                        .build();
            }
            if (bodySpec != null) {
                bodySpec.accept(builder);
            }
        });
    }

    private static List<AccountAmount> mergedAdjustments(@NonNull final List<AccountAmount> adjustments) {
        requireNonNull(adjustments);
        final Map<com.hederahashgraph.api.proto.java.AccountID, AccountAmount.Builder> mergedByAccount =
                new LinkedHashMap<>();
        for (final var adjustment : adjustments) {
            final var accountId = adjustment.getAccountID();
            var merged = mergedByAccount.get(accountId);
            if (merged == null) {
                mergedByAccount.put(accountId, adjustment.toBuilder());
                continue;
            }
            merged.setAmount(Math.addExact(merged.getAmount(), adjustment.getAmount()));
            if (adjustment.hasPreTxAllowanceHook()) {
                if (merged.hasPreTxAllowanceHook()) {
                    if (!merged.getPreTxAllowanceHook().equals(adjustment.getPreTxAllowanceHook())) {
                        throw new IllegalArgumentException("Cannot merge differing pre-tx hooks for " + accountId);
                    }
                } else {
                    merged.setPreTxAllowanceHook(adjustment.getPreTxAllowanceHook());
                }
            }
            if (adjustment.hasPrePostTxAllowanceHook()) {
                if (merged.hasPrePostTxAllowanceHook()) {
                    if (!merged.getPrePostTxAllowanceHook().equals(adjustment.getPrePostTxAllowanceHook())) {
                        throw new IllegalArgumentException("Cannot merge differing pre/post hooks for " + accountId);
                    }
                } else {
                    merged.setPrePostTxAllowanceHook(adjustment.getPrePostTxAllowanceHook());
                }
            }
        }
        final List<AccountAmount> mergedAdjustments = new ArrayList<>();
        for (final var merged : mergedByAccount.values()) {
            if (merged.getAmount() != 0) {
                mergedAdjustments.add(merged.build());
            }
        }
        return mergedAdjustments;
    }

    /**
     * Returns a {@link SpecOperation} that will call the {@code mockNextRevert} function on the given
     * {@code mockSupraRegistry} contract.
     * @param registry the contract to call the {@code mockNextRevert} function on
     * @return a {@link SpecOperation} that configures the mock to revert the next proof verification
     */
    public SpecOperation revertNextProofVerify(@NonNull final SpecContract registry) {
        requireNonNull(registry);
        return registry.call("mockNextRevert").gas(ORACLE_SETUP_GAS);
    }

    /**
     * Returns a {@link SpecOperation} that configures the {@code mockSupraRegistry} contract to return the
     * given price for the given pair ID on the next proof verification.
     * <p>
     * Uses whatever scale the given {@link BigDecimal} has to choose {@code decimals} for the {@code PriceInfo}
     * struct response.
     * @param registry the contract to call the {@code mockNextRevert} function on
     * @param pairId which pair to return the price for
     * @param price the price to return for the given pair
     * @param pullAgeSeconds the approximate age of the price to return, in seconds before the current time
     * @return a {@link SpecOperation} that configures the mock to revert the next proof verification
     */
    public SpecOperation answerNextProofVerify(
            @NonNull final SpecContract registry,
            @NonNull final BigInteger pairId,
            @NonNull final BigDecimal price,
            final int pullAgeSeconds) {
        requireNonNull(registry);
        return sourcingContextual(spec -> {
            final var timestamp = BigInteger.valueOf(spec.consensusTime().getEpochSecond())
                    .subtract(BigInteger.valueOf(pullAgeSeconds));
            return registry.call(
                            "mockNextPriceInfo",
                            pairId,
                            price.unscaledValue(),
                            timestamp,
                            BigInteger.valueOf(price.scale()),
                            BigInteger.ONE)
                    .gas(ORACLE_SETUP_GAS);
        });
    }

    /**
     * Returns a "poke" transfer with only a zero-balance HBAR transfer list carrying hook data with
     * {@code prefix || uint256(proof.length) || proof}.
     */
    public HapiCryptoTransfer pokeWithOracleProof(
            @NonNull final SpecAccount account, @NonNull final String b64Salt, @NonNull final Bytes proof) {
        requireNonNull(account);
        requireNonNull(b64Salt);
        requireNonNull(proof);
        return TxnVerbs.cryptoTransfer((spec, builder) -> {
            final var accountId = spec.registry().getAccountID(account.name());
            final var prefix = requireNonNull(saltPrefixes.get(b64Salt));
            final var proofLen =
                    leftPad32(Bytes.wrap(BigInteger.valueOf(proof.length()).toByteArray()));
            final var data = prefix.append(proofLen).append(proof);
            saltPrefixes.put(b64Salt, convertedPokePrefix(prefix));
            builder.setTransfers(TransferList.newBuilder()
                    .addAccountAmounts(AccountAmount.newBuilder()
                            .setPreTxAllowanceHook(HookCall.newBuilder()
                                    .setHookId(hookId)
                                    .setEvmHookCall(EvmHookCall.newBuilder()
                                            .setGasLimit(ORACLE_HOOK_GAS)
                                            .setData(fromPbj(data))))
                            .setAmount(0)
                            .setAccountID(accountId)
                            .build())
                    .build());
        });
    }

    /**
     * Rebinds a known stop-order salt to its converted key type after a trigger-and-convert path.
     * <p>
     * This updates only local test bookkeeping so subsequent assertions/settlements use the converted key.
     */
    public SpecOperation rebindSaltAfterStopConversion(@NonNull final String b64Salt) {
        requireNonNull(b64Salt);
        return doingContextual(spec -> {
            final var prefix = requireNonNull(saltPrefixes.get(b64Salt));
            saltPrefixes.put(b64Salt, convertedPokePrefix(prefix));
        });
    }

    private static Bytes convertedPokePrefix(@NonNull final Bytes prefix) {
        requireNonNull(prefix);
        final var raw = prefix.toByteArray();
        switch (raw[0]) {
            case STOP_LIMIT_LT, STOP_LIMIT_GT -> raw[0] = LIMIT;
            case STOP_MARKET_LT, STOP_MARKET_GT -> raw[0] = MARKET;
            default -> {
                return prefix;
            }
        }
        return Bytes.wrap(raw);
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
            final int minFillCentiBps,
            @Nullable final LongUnaryOperator expiryTransform,
            @Nullable final UnaryOperator<Bytes> detailTransform,
            @Nullable final UnaryOperator<Bytes> keyTransform) {
        return sourcingContextual(spec -> {
            final var targetNetwork = spec.targetNetworkOrThrow();
            final var baseToken = specBaseToken.tokenOrThrow(targetNetwork);
            final var quoteToken = specQuoteToken.tokenOrThrow(targetNetwork);
            final var baseAmount = toBigInteger(quantity, baseToken.decimals());
            final var quotePrice = Fraction.from(price, baseToken.decimals(), quoteToken.decimals());
            final Token inToken;
            final Token outToken;
            final Bytes detailValue;
            if (side == BUY) {
                // User is debited quote token
                outToken = quoteToken;
                // And credited base token
                inToken = baseToken;
                detailValue = encodeOrderDetailValue(
                        // So storage mapping entry amount is in quote token units
                        mulDiv(baseAmount, quotePrice.numerator(), quotePrice.denominator()),
                        // With a price in base/quote terms
                        quotePrice.biDenominator(),
                        quotePrice.biNumerator(),
                        BigInteger.valueOf(priceDeviationCentiBps),
                        BigInteger.valueOf(minFillCentiBps));
            } else {
                // User is debited base token
                outToken = baseToken;
                // And credited quote token
                inToken = quoteToken;
                detailValue = encodeOrderDetailValue(
                        // So storage mapping entry amount is in base token units
                        baseAmount,
                        // With a price in quote/base terms
                        quotePrice.biNumerator(),
                        quotePrice.biDenominator(),
                        BigInteger.valueOf(priceDeviationCentiBps),
                        BigInteger.valueOf(minFillCentiBps));
            }
            long expirySecond = expiry.getEpochSecond();
            if (expiryTransform != null) {
                expirySecond = expiryTransform.applyAsLong(expirySecond);
            }
            var prefixKey = encodeOrderPrefixKey(
                    orderType,
                    stopDirection,
                    inToken.tokenIdOrThrow().tokenNum(),
                    outToken.tokenIdOrThrow().tokenNum(),
                    expirySecond,
                    feeCentiBps,
                    b64Salt);
            if (keyTransform != null) {
                prefixKey = keyTransform.apply(prefixKey);
            }
            saltPrefixes.put(b64Salt, prefixKey);
            saltQuantityDecimals.put(b64Salt, outToken.decimals());
            var details = minimalKey(detailValue);
            if (detailTransform != null) {
                details = detailTransform.apply(details);
            }
            return accountEvmHookStore(account.name(), hookId).putMappingEntryWithKey(Bytes.EMPTY, prefixKey, details);
        });
    }

    private static Bytes encodeOrderPrefixKey(
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

    private static Bytes encodeOrderDetailValue(
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

    private static byte asPrefixType(@NonNull final OrderType type, @Nullable final StopDirection stopDirection) {
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

    private static BigInteger mulDiv(BigInteger v, long n, long d) {
        if (d == 0) {
            throw new IllegalArgumentException("Denominator must be non-zero");
        }
        return v.multiply(BigInteger.valueOf(n)).divide(BigInteger.valueOf(d));
    }
}
