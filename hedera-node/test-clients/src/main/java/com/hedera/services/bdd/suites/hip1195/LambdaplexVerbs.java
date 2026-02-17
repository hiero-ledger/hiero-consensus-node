// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore.minimalKey;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.hip1195.Role.MAKER;
import static com.hedera.services.bdd.suites.hip1195.Role.TAKER;
import static com.hedera.services.bdd.suites.hip1195.Side.BUY;
import static com.hedera.services.bdd.suites.hip1195.Side.SELL;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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

        public static FillParty makingSeller(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Seller debits base, credits quote
            return new FillParty(
                    account,
                    MAKER,
                    SELL,
                    quantity.negate(),
                    quantity.multiply(averagePrice),
                    BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                    b64Salts);
        }

        public static FillParty takingSeller(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Seller debits base, credits quote
            return new FillParty(
                    account,
                    TAKER,
                    SELL,
                    quantity.negate(),
                    quantity.multiply(averagePrice),
                    BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                    b64Salts);
        }

        public static FillParty takingBuyer(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Buyer debits quote, credits base
            return new FillParty(
                    account,
                    TAKER,
                    BUY,
                    quantity.multiply(averagePrice).negate(),
                    quantity,
                    BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                    b64Salts);
        }

        public static FillParty makingBuyer(
                SpecAccount account, BigDecimal quantity, BigDecimal averagePrice, String... b64Salts) {
            // Buyer debits quote, credits base
            return new FillParty(
                    account,
                    MAKER,
                    BUY,
                    quantity.multiply(averagePrice).negate(),
                    quantity,
                    BASE_GAS_LIMIT + DIRECT_PREFIX_GAS_INCREMENT * b64Salts.length,
                    b64Salts);
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
                        .multiply(BigDecimal.valueOf(100_00))
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
        return settleFillsInternal(specBaseToken, specQuoteToken, fees, null, null, fillParties);
    }

    public HapiCryptoTransfer settleFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, null, null, fillParties);
    }

    public HapiCryptoTransfer settleBodyTransformedFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Consumer<CryptoTransferTransactionBody.Builder> bodySpec,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, bodySpec, null, fillParties);
    }

    public HapiCryptoTransfer settleDataTransformedFillsNoFees(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final UnaryOperator<Bytes> dataTransform,
            @NonNull final LambdaplexVerbs.FillParty... fillParties) {
        return settleFillsInternal(specBaseToken, specQuoteToken, null, null, dataTransform, fillParties);
    }

    private HapiCryptoTransfer settleFillsInternal(
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @Nullable final Fees fees,
            @Nullable final Consumer<CryptoTransferTransactionBody.Builder> bodySpec,
            @Nullable final UnaryOperator<Bytes> dataTransform,
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
            final var registry = spec.registry();
            if (specBaseToken == HBAR) {
                builder.setTransfers(TransferList.newBuilder().addAllAccountAmounts(baseAdjustments))
                        .addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specQuoteToken.name()))
                                .addAllTransfers(quoteAdjustments))
                        .build();
            } else if (specQuoteToken == HBAR) {
                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specBaseToken.name()))
                                .addAllTransfers(baseAdjustments))
                        .setTransfers(TransferList.newBuilder().addAllAccountAmounts(quoteAdjustments))
                        .build();
            } else {
                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specBaseToken.name()))
                                .addAllTransfers(baseAdjustments))
                        .addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(registry.getTokenID(specQuoteToken.name()))
                                .addAllTransfers(quoteAdjustments))
                        .build();
            }
            if (bodySpec != null) {
                bodySpec.accept(builder);
            }
        });
    }

    /**
     * Returns a {@link SpecOperation} that will call the {@code mockNextRevert} function on the given
     * {@code mockSupraRegistry} contract.
     * @param registry the contract to call the {@code mockNextRevert} function on
     * @return a {@link SpecOperation} that configures the mock to revert the next proof verification
     */
    public SpecOperation revertNextProofVerify(@NonNull final SpecContract registry) {
        requireNonNull(registry);
        return registry.call("mockNextRevert");
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
                    BigInteger.ONE);
        });
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
