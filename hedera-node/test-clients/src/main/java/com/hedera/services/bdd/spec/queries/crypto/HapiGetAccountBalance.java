// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.crypto;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.GetAccountDetailsQuery;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Gets the balance of an account from the account details query.
 */
public class HapiGetAccountBalance extends HapiQueryOp<HapiGetAccountBalance> {
    private static final Logger log = LogManager.getLogger(HapiGetAccountBalance.class);

    private String account;
    Optional<Long> expected = Optional.empty();
    Optional<Supplier<String>> entityFn = Optional.empty();
    Optional<Function<HapiSpec, Function<Long, Optional<String>>>> expectedTinybarCondition = Optional.empty();
    Optional<Map<String, LongConsumer>> tokenBalanceObservers = Optional.empty();

    @Nullable
    private Map<String, Function<HapiSpec, Function<Long, Optional<String>>>> expectedTokenConditions = null;

    @Nullable
    LongConsumer balanceObserver;

    private String repr;

    private AccountID expectedId = null;
    private String aliasKeySource = null;
    private String literalHexedAlias = null;
    private ReferenceType referenceType = ReferenceType.REGISTRY_NAME;
    private boolean isContract = false;
    private boolean assertAccountIDIsNotAlias = false;
    private ByteString rawAlias;

    private boolean includeTokenMemoOnError = false;
    List<Map.Entry<String, String>> expectedTokenBalances = Collections.EMPTY_LIST;

    public HapiGetAccountBalance(String account) {
        this(account, ReferenceType.REGISTRY_NAME);
    }

    public HapiGetAccountBalance(String account, boolean isContract) {
        this(account, ReferenceType.REGISTRY_NAME);
        this.isContract = isContract;
    }

    public HapiGetAccountBalance(String reference, ReferenceType type) {
        this.referenceType = type;
        if (type == ReferenceType.ALIAS_KEY_NAME) {
            aliasKeySource = reference;
            repr = "KeyAlias(" + aliasKeySource + ")";
        } else if (type == ReferenceType.HEXED_CONTRACT_ALIAS) {
            literalHexedAlias = reference;
            repr = reference;
        } else {
            account = reference;
            repr = account;
        }
    }

    public HapiGetAccountBalance(ByteString rawAlias, ReferenceType type) {
        this.rawAlias = rawAlias;
        this.referenceType = type;
    }

    public HapiGetAccountBalance(Supplier<String> supplier) {
        this.entityFn = Optional.of(supplier);
    }

    public HapiGetAccountBalance hasTinyBars(long amount) {
        expected = Optional.of(amount);
        return this;
    }

    public HapiGetAccountBalance hasTokenBalance(
            String token, Function<HapiSpec, Function<Long, Optional<String>>> condition) {
        if (expectedTokenConditions == null) {
            expectedTokenConditions = new HashMap<>();
        }
        expectedTokenConditions.put(token, condition);
        return this;
    }

    public HapiGetAccountBalance includeTokenMemoOnError() {
        includeTokenMemoOnError = true;
        return this;
    }

    public HapiGetAccountBalance hasTinyBars(Function<HapiSpec, Function<Long, Optional<String>>> condition) {
        expectedTinybarCondition = Optional.of(condition);
        return this;
    }

    public HapiGetAccountBalance hasNoTokenBalancesReturned() {
        expectedTokenBalances = new ArrayList<>();
        return this;
    }

    public HapiGetAccountBalance hasTokenBalance(String token, long amount) {
        if (expectedTokenBalances.isEmpty()) {
            expectedTokenBalances = new ArrayList<>();
        }
        expectedTokenBalances.add(new AbstractMap.SimpleImmutableEntry<>(token, amount + "-G"));
        return this;
    }

    public HapiGetAccountBalance hasTokenBalance(String token, long amount, int decimals) {
        if (expectedTokenBalances.isEmpty()) {
            expectedTokenBalances = new ArrayList<>();
        }
        expectedTokenBalances.add(new AbstractMap.SimpleImmutableEntry<>(token, amount + "-" + decimals));
        return this;
    }

    public HapiGetAccountBalance savingTokenBalance(String token, LongConsumer obs) {
        if (tokenBalanceObservers.isEmpty()) {
            tokenBalanceObservers = Optional.of(new HashMap<>());
        }
        tokenBalanceObservers.get().put(token, obs);
        return this;
    }

    public HapiGetAccountBalance exposingBalanceTo(final LongConsumer obs) {
        balanceObserver = obs;
        return this;
    }

    public HapiGetAccountBalance hasExpectedAccountID() {
        assertAccountIDIsNotAlias = true;
        return this;
    }

    public HapiGetAccountBalance hasId(final AccountID expectedId) {
        this.expectedId = expectedId;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.GetAccountDetails;
    }

    @Override
    protected HapiGetAccountBalance self() {
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        payer = Optional.of(spec.setup().genesisAccountName());
        return super.submitOp(spec);
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        final var details = response.getAccountDetails().getAccountDetails();
        long actual = details.getBalance();
        if (balanceObserver != null) {
            balanceObserver.accept(actual);
        }
        if (verboseLoggingOn) {
            String message = String.format("Explicit token balances: %s", details.getTokenRelationshipsList());
            log.info(message);
        }

        if (assertAccountIDIsNotAlias) {
            final var expectedID = spec.registry()
                    .getAccountID(spec.registry()
                            .getKey(aliasKeySource)
                            .toByteString()
                            .toStringUtf8());
            assertEquals(expectedID, details.getAccountId());
        }

        if (expectedId != null) {
            assertEquals(expectedId, details.getAccountId(), "Wrong account id");
        }

        if (expectedTinybarCondition.isPresent()) {
            Function<Long, Optional<String>> condition =
                    expectedTinybarCondition.get().apply(spec);
            Optional<String> failure = condition.apply(actual);
            if (failure.isPresent()) {
                Assertions.fail("Bad balance! :: " + failure.get());
            }
        } else if (expected.isPresent()) {
            assertEquals(expected.get().longValue(), actual, "Wrong balance!");
        }

        if (!expectedTokenBalances.isEmpty() || tokenBalanceObservers.isPresent() || expectedTokenConditions != null) {
            Map<TokenID, Pair<Long, Integer>> actualTokenBalances = details.getTokenRelationshipsList().stream()
                    .collect(Collectors.toMap(tr -> tr.getTokenId(), tr -> Pair.of(tr.getBalance(), tr.getDecimals())));
            if (expectedTokenConditions != null) {
                expectedTokenConditions.forEach((key, value) -> {
                    final var tokenId = asTokenId(key, spec);
                    final var condition = value.apply(spec);
                    final long actualBalance = actualTokenBalances
                            .getOrDefault(tokenId, Pair.of(0L, 0))
                            .getLeft();
                    condition
                            .apply(actualBalance)
                            .ifPresent(s -> Assertions.fail("Bad token balance (" + tokenId.toString() + " was "
                                    + actualBalance + ") :: " + s));
                });
            }
            Pair<Long, Integer> defaultTb = Pair.of(0L, 0);
            for (Map.Entry<String, String> tokenBalance : expectedTokenBalances) {
                var tokenId = asTokenId(tokenBalance.getKey(), spec);
                String[] expectedParts = tokenBalance.getValue().split("-");
                Long expectedBalance = Long.valueOf(expectedParts[0]);
                try {
                    assertEquals(
                            expectedBalance,
                            actualTokenBalances.getOrDefault(tokenId, defaultTb).getLeft(),
                            String.format("Wrong balance for token '%s'!", HapiPropertySource.asTokenString(tokenId)));
                } catch (AssertionError e) {
                    if (includeTokenMemoOnError) {
                        final var lookup = QueryVerbs.getTokenInfo(
                                tokenId.getShardNum() + "." + tokenId.getRealmNum() + "." + tokenId.getTokenNum());
                        allRunFor(spec, lookup);
                        final var memo = lookup.getResponse()
                                .getTokenGetInfo()
                                .getTokenInfo()
                                .getMemo();
                        Assertions.fail(e.getMessage() + " - M'" + memo + "'");
                    } else {
                        throw e;
                    }
                }
                if (!"G".equals(expectedParts[1])) {
                    Integer expectedDecimals = Integer.valueOf(expectedParts[1]);
                    assertEquals(
                            expectedDecimals,
                            actualTokenBalances.getOrDefault(tokenId, defaultTb).getRight(),
                            String.format("Wrong decimals for token '%s'!", HapiPropertySource.asTokenString(tokenId)));
                }
            }
            if (tokenBalanceObservers.isPresent()) {
                var observers = tokenBalanceObservers.get();
                for (var entry : observers.entrySet()) {
                    var id = TxnUtils.asTokenId(entry.getKey(), spec);
                    var obs = entry.getValue();
                    obs.accept(actualTokenBalances
                            .getOrDefault(id, Pair.of(-1L, -1))
                            .getLeft());
                }
            }
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var detailsResponse = response.getAccountDetails();
        final var status = detailsResponse.getHeader().getNodeTransactionPrecheckCode();
        if (status == ResponseCodeEnum.ACCOUNT_DELETED) {
            String message = String.format("%s%s was actually deleted!", spec.logPrefix(), repr);
            log.info(message);
        } else {
            long balance = detailsResponse.getAccountDetails().getBalance();
            long TINYBARS_PER_HBAR = 100_000_000L;
            long hBars = balance / TINYBARS_PER_HBAR;
            if (!loggingOff) {
                String message =
                        String.format("%sbalance for '%s':%d tinyBars (%dh)", spec.logPrefix(), repr, balance, hBars);
                log.info(message);
            }
            if (yahcliLogger) {
                System.out.println(".i. " + String.format("%20s | %20d |", repr, balance));
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getAccountDetailsQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getAccountDetailsQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        if (entityFn.isPresent()) {
            account = entityFn.get().get();
            repr = account;
        }

        AccountID target;
        if (isContract || spec.registry().hasContractId(account)) {
            final var contractId = TxnUtils.asContractId(account, spec);
            final var targetBuilder =
                    AccountID.newBuilder().setShardNum(contractId.getShardNum()).setRealmNum(contractId.getRealmNum());
            if (contractId.hasEvmAddress()) {
                targetBuilder.setAlias(contractId.getEvmAddress());
            } else {
                targetBuilder.setAccountNum(contractId.getContractNum());
            }
            target = targetBuilder.build();
        } else if (referenceType == ReferenceType.HEXED_CONTRACT_ALIAS) {
            target = AccountID.newBuilder()
                    .setShardNum(spec.shard())
                    .setRealmNum(spec.realm())
                    .setAlias(TxnUtils.asLiteralEvmAddress(literalHexedAlias))
                    .build();
        } else {
            if (referenceType == ReferenceType.REGISTRY_NAME) {
                target = TxnUtils.asId(account, spec);
            } else if (referenceType == ReferenceType.LITERAL_ACCOUNT_ALIAS) {
                target = AccountID.newBuilder()
                        .setShardNum(spec.shard())
                        .setRealmNum(spec.realm())
                        .setAlias(rawAlias)
                        .build();
            } else {
                target = spec.registry().keyAliasIdFor(spec, aliasKeySource);
            }
        }
        final var query = GetAccountDetailsQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setAccountId(target)
                .build();
        return Query.newBuilder().setAccountDetails(query).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("account", account);
    }
}
