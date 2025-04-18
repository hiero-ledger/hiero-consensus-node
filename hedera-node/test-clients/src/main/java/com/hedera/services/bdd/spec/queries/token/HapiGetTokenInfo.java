// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.token;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenGetInfoQuery;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiGetTokenInfo extends HapiQueryOp<HapiGetTokenInfo> {
    private static final Logger LOG = LogManager.getLogger(HapiGetTokenInfo.class);

    private final String token;

    public HapiGetTokenInfo(String token) {
        this.token = token;
    }

    private boolean searchKeysGlobally;
    private Optional<TokenType> expectedTokenType = Optional.empty();
    private Optional<TokenSupplyType> expectedSupplyType = Optional.empty();
    private OptionalInt expectedDecimals = OptionalInt.empty();
    private OptionalLong expectedTotalSupply = OptionalLong.empty();

    @Nullable
    private LongConsumer totalSupplyAssertion = null;

    private OptionalLong expectedMaxSupply = OptionalLong.empty();
    private Optional<String> expectedMemo = Optional.empty();
    private Optional<String> expectedMetadata = Optional.empty();
    private Optional<String> expectedMetadataKey = Optional.empty();
    private Optional<String> expectedId = Optional.empty();
    private Optional<String> expectedSymbol = Optional.empty();
    private Optional<String> expectedName = Optional.empty();
    private Optional<String> expectedTreasury = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitAdminKey;

    private Optional<String> expectedAdminKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitKycKey;

    private Optional<String> expectedKycKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitFreezeKey;

    private Optional<String> expectedFreezeKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitSupplyKey;

    private Optional<String> expectedSupplyKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitWipeKey;

    private Optional<String> expectedWipeKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitFeeScheduleKey;

    private Optional<String> expectedFeeScheduleKey = Optional.empty();

    @Nullable
    private com.hedera.hapi.node.base.Key explicitPauseKey;

    private Optional<String> expectedPauseKey = Optional.empty();
    private boolean emptyAdminKey = false;
    private boolean emptyWipeKey = false;
    private boolean emptyKycKey = false;
    private boolean emptySupplyKey = false;
    private boolean emptyFreezeKey = false;
    private boolean emptyFeeScheduleKey = false;
    private boolean emptyMetadataKey = false;
    private boolean emptyPauseKey = false;

    @SuppressWarnings("java:S1068")
    private Optional<Boolean> expectedDeletion = Optional.empty();

    private Optional<TokenPauseStatus> expectedPauseStatus = Optional.empty();

    @SuppressWarnings("java:S1068")
    private Optional<TokenKycStatus> expectedKycDefault = Optional.empty();

    @SuppressWarnings("java:S1068")
    private Optional<TokenFreezeStatus> expectedFreezeDefault = Optional.empty();

    private Optional<String> expectedAutoRenewAccount = Optional.empty();
    private OptionalLong expectedAutoRenewPeriod = OptionalLong.empty();
    private Optional<Boolean> expectedExpiry = Optional.empty();
    private List<BiConsumer<HapiSpec, List<CustomFee>>> expectedFees = new ArrayList<>();

    private long explicitExpiry = -1;

    @Nullable
    private Function<TokenInfo, SpecOperation> validationOp = null;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenGetInfo;
    }

    @Override
    protected HapiGetTokenInfo self() {
        return this;
    }

    public HapiGetTokenInfo hasTokenType(TokenType t) {
        expectedTokenType = Optional.of(t);
        return this;
    }

    /**
     * Add a validation operation to be run on the token info.
     *
     * @param validationOp the validation operation
     * @return {@code this}
     */
    public HapiGetTokenInfo andVerify(@NonNull final Function<TokenInfo, SpecOperation> validationOp) {
        this.validationOp = validationOp;
        return this;
    }

    public HapiGetTokenInfo hasSupplyType(TokenSupplyType t) {
        expectedSupplyType = Optional.of(t);
        return this;
    }

    public HapiGetTokenInfo hasFreezeDefault(TokenFreezeStatus s) {
        expectedFreezeDefault = Optional.of(s);
        return this;
    }

    public HapiGetTokenInfo hasKycDefault(TokenKycStatus s) {
        expectedKycDefault = Optional.of(s);
        return this;
    }

    public HapiGetTokenInfo hasDecimals(int d) {
        expectedDecimals = OptionalInt.of(d);
        return this;
    }

    public HapiGetTokenInfo hasMaxSupply(long amount) {
        expectedMaxSupply = OptionalLong.of(amount);
        return this;
    }

    public HapiGetTokenInfo hasTotalSupply(long amount) {
        expectedTotalSupply = OptionalLong.of(amount);
        return this;
    }

    public HapiGetTokenInfo hasTotalSupplySatisfying(@NonNull final LongConsumer assertion) {
        totalSupplyAssertion = Objects.requireNonNull(assertion);
        return this;
    }

    public HapiGetTokenInfo hasRegisteredId(String token) {
        expectedId = Optional.of(token);
        return this;
    }

    public HapiGetTokenInfo hasEntityMemo(String memo) {
        expectedMemo = Optional.of(memo);
        return this;
    }

    public HapiGetTokenInfo hasMetadata(String metadata) {
        expectedMetadata = Optional.of(metadata);
        return this;
    }

    public HapiGetTokenInfo hasMetadataKey(String name) {
        expectedMetadataKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasAutoRenewPeriod(Long renewPeriod) {
        expectedAutoRenewPeriod = OptionalLong.of(renewPeriod);
        return this;
    }

    public HapiGetTokenInfo hasAutoRenewAccount(String account) {
        expectedAutoRenewAccount = Optional.of(account);
        return this;
    }

    public HapiGetTokenInfo hasValidExpiry() {
        expectedExpiry = Optional.of(true);
        return this;
    }

    public HapiGetTokenInfo hasExpiry(final long expiry) {
        explicitExpiry = expiry;
        return this;
    }

    public HapiGetTokenInfo hasSymbol(String token) {
        expectedSymbol = Optional.of(token);
        return this;
    }

    public HapiGetTokenInfo hasName(String name) {
        expectedName = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasTreasury(String name) {
        expectedTreasury = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasFreezeKey(String name) {
        expectedFreezeKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasFreezeKey(com.hedera.hapi.node.base.Key key) {
        explicitFreezeKey = key;
        return this;
    }

    public HapiGetTokenInfo hasAdminKey(String name) {
        expectedAdminKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasAdminKey(com.hedera.hapi.node.base.Key key) {
        explicitAdminKey = key;
        return this;
    }

    public HapiGetTokenInfo hasPauseKey(com.hedera.hapi.node.base.Key key) {
        explicitPauseKey = key;
        return this;
    }

    public HapiGetTokenInfo hasPauseKey(String name) {
        expectedPauseKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasPauseStatus(TokenPauseStatus status) {
        expectedPauseStatus = Optional.of(status);
        return this;
    }

    public HapiGetTokenInfo hasKycKey(com.hedera.hapi.node.base.Key key) {
        explicitKycKey = key;
        return this;
    }

    public HapiGetTokenInfo hasKycKey(String name) {
        expectedKycKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasSupplyKey(com.hedera.hapi.node.base.Key key) {
        explicitSupplyKey = key;
        return this;
    }

    public HapiGetTokenInfo hasSupplyKey(String name) {
        expectedSupplyKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasWipeKey(com.hedera.hapi.node.base.Key key) {
        explicitWipeKey = key;
        return this;
    }

    public HapiGetTokenInfo hasWipeKey(String name) {
        expectedWipeKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo hasFeeScheduleKey(com.hedera.hapi.node.base.Key key) {
        explicitFeeScheduleKey = key;
        return this;
    }

    public HapiGetTokenInfo hasFeeScheduleKey(String name) {
        expectedFeeScheduleKey = Optional.of(name);
        return this;
    }

    public HapiGetTokenInfo isDeleted() {
        expectedDeletion = Optional.of(Boolean.TRUE);
        return this;
    }

    public HapiGetTokenInfo isNotDeleted() {
        expectedDeletion = Optional.of(Boolean.FALSE);
        return this;
    }

    public HapiGetTokenInfo hasCustom(BiConsumer<HapiSpec, List<CustomFee>> feeAssertion) {
        expectedFees.add(feeAssertion);
        return this;
    }

    public HapiGetTokenInfo hasEmptyCustom() {
        expectedFees = new ArrayList<>();
        return this;
    }

    public HapiGetTokenInfo searchKeysGlobally() {
        searchKeysGlobally = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyAdminKey() {
        emptyAdminKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyPauseKey() {
        emptyPauseKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyFreezeKey() {
        emptyFreezeKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyKycKey() {
        emptyKycKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptySupplyKey() {
        emptySupplyKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyWipeKey() {
        emptyWipeKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyFeeScheduleKey() {
        emptyFeeScheduleKey = true;
        return this;
    }

    public HapiGetTokenInfo hasEmptyMetadataKey() {
        emptyMetadataKey = true;
        return this;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) {
        var actualInfo = response.getTokenGetInfo().getTokenInfo();

        if (validationOp != null) {
            allRunFor(spec, validationOp.apply(actualInfo));
        }

        expectedTokenType.ifPresent(
                tokenType -> assertEquals(tokenType, actualInfo.getTokenType(), "Wrong token type!"));

        expectedSupplyType.ifPresent(
                supplyType -> assertEquals(supplyType, actualInfo.getSupplyType(), "Wrong supply type!"));

        if (expectedSymbol.isPresent()) {
            assertEquals(expectedSymbol.get(), actualInfo.getSymbol(), "Wrong symbol!");
        }

        if (expectedName.isPresent()) {
            assertEquals(expectedName.get(), actualInfo.getName(), "Wrong name!");
        }

        if (expectedAutoRenewAccount.isPresent()) {
            var id = TxnUtils.asId(expectedAutoRenewAccount.get(), spec);
            assertEquals(id, actualInfo.getAutoRenewAccount(), "Wrong auto renew account!");
        }

        if (expectedAutoRenewPeriod.isPresent()) {
            assertEquals(
                    expectedAutoRenewPeriod.getAsLong(),
                    actualInfo.getAutoRenewPeriod().getSeconds(),
                    "Wrong auto renew period!");
        }

        if (expectedMaxSupply.isPresent()) {
            assertEquals(expectedMaxSupply.getAsLong(), actualInfo.getMaxSupply(), "Wrong max supply!");
        }

        if (expectedTotalSupply.isPresent()) {
            assertEquals(expectedTotalSupply.getAsLong(), actualInfo.getTotalSupply(), "Wrong total supply!");
        }
        if (totalSupplyAssertion != null) {
            totalSupplyAssertion.accept(actualInfo.getTotalSupply());
        }

        if (expectedDecimals.isPresent()) {
            assertEquals(expectedDecimals.getAsInt(), actualInfo.getDecimals(), "Wrong decimals!");
        }

        if (expectedTreasury.isPresent()) {
            var id = TxnUtils.asId(expectedTreasury.get(), spec);
            assertEquals(id, actualInfo.getTreasury(), "Wrong treasury account!");
        }

        expectedPauseStatus.ifPresent(
                status -> assertEquals(status, actualInfo.getPauseStatus(), "wrong Pause status"));

        final var actualFees = actualInfo.getCustomFeesList();
        for (var expectedFee : expectedFees) {
            expectedFee.accept(spec, actualFees);
        }

        expectedMemo.ifPresent(s -> assertEquals(s, actualInfo.getMemo(), "Wrong memo!"));
        expectedMetadata.ifPresent(s -> assertEquals(s, actualInfo.getMetadata().toStringUtf8(), "Wrong metadata!"));

        var registry = spec.registry();
        assertFor(actualInfo.getTokenId(), expectedId, (n, r) -> r.getTokenID(n), "Wrong token id!", registry);
        assertFor(
                actualInfo.getExpiry(),
                expectedExpiry,
                (n, r) -> Timestamp.newBuilder().setSeconds(r.getExpiry(token)).build(),
                "Wrong token expiry!",
                registry);

        if (explicitExpiry != -1) {
            assertEquals(explicitExpiry, actualInfo.getExpiry().getSeconds(), "Wrong token expiry");
        }

        if (emptyFreezeKey) {
            assertForRemovedKey(actualInfo.getFreezeKey());
        } else if (explicitFreezeKey != null) {
            assertEquals(fromPbj(explicitFreezeKey), actualInfo.getFreezeKey(), "Wrong token freeze key!");
        } else {
            assertFor(
                    actualInfo.getFreezeKey(),
                    expectedFreezeKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.FREEZE),
                    "Wrong token freeze key!",
                    registry);
        }

        if (emptyAdminKey) {
            assertForRemovedKey(actualInfo.getAdminKey());
        } else if (explicitAdminKey != null) {
            assertEquals(fromPbj(explicitAdminKey), actualInfo.getAdminKey(), "Wrong token admin key!");
        } else {
            assertFor(
                    actualInfo.getAdminKey(),
                    expectedAdminKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.ADMIN),
                    "Wrong token admin key!",
                    registry);
        }

        if (emptyWipeKey) {
            assertForRemovedKey(actualInfo.getWipeKey());
        } else if (explicitWipeKey != null) {
            assertEquals(fromPbj(explicitWipeKey), actualInfo.getWipeKey(), "Wrong token wipe key!");
        } else {
            assertFor(
                    actualInfo.getWipeKey(),
                    expectedWipeKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.WIPE),
                    "Wrong token wipe key!",
                    registry);
        }

        if (emptyKycKey) {
            assertForRemovedKey(actualInfo.getKycKey());
        } else if (explicitKycKey != null) {
            assertEquals(fromPbj(explicitKycKey), actualInfo.getKycKey(), "Wrong token KYC key!");
        } else {
            assertFor(
                    actualInfo.getKycKey(),
                    expectedKycKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.KYC),
                    "Wrong token KYC key!",
                    registry);
        }

        if (emptySupplyKey) {
            assertForRemovedKey(actualInfo.getSupplyKey());
        } else if (explicitSupplyKey != null) {
            assertEquals(fromPbj(explicitSupplyKey), actualInfo.getSupplyKey(), "Wrong token supply key!");
        } else {
            assertFor(
                    actualInfo.getSupplyKey(),
                    expectedSupplyKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.SUPPLY),
                    "Wrong token supply key!",
                    registry);
        }

        if (emptyFeeScheduleKey) {
            assertForRemovedKey(actualInfo.getFeeScheduleKey());
        } else if (explicitFeeScheduleKey != null) {
            assertEquals(
                    fromPbj(explicitFeeScheduleKey), actualInfo.getFeeScheduleKey(), "Wrong token fee schedule key!");
        } else {
            assertFor(
                    actualInfo.getFeeScheduleKey(),
                    expectedFeeScheduleKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.FEE_SCHEDULE),
                    "Wrong token fee schedule key!",
                    registry);
        }

        if (emptyPauseKey) {
            assertForRemovedKey(actualInfo.getPauseKey());
        } else if (explicitPauseKey != null) {
            assertEquals(fromPbj(explicitPauseKey), actualInfo.getPauseKey(), "Wrong token pause key!");
        } else {
            assertFor(
                    actualInfo.getPauseKey(),
                    expectedPauseKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.PAUSE),
                    "Wrong token pause key!",
                    registry);
        }

        if (emptyMetadataKey) {
            assertForRemovedKey(actualInfo.getMetadataKey());
        } else {
            assertFor(
                    actualInfo.getMetadataKey(),
                    expectedMetadataKey,
                    (n, r) -> searchKeysGlobally ? r.getKey(n) : r.getRoleKey(n, KeyRole.METADATA),
                    "Wrong token metadata key!",
                    registry);
        }

        expectedLedgerId.ifPresent(id -> assertEquals(id, actualInfo.getLedgerId()));
    }

    private <T, R> void assertFor(
            R actual,
            Optional<T> possible,
            BiFunction<T, HapiSpecRegistry, R> expectedFn,
            String error,
            HapiSpecRegistry registry) {
        if (possible.isPresent()) {
            var expected = expectedFn.apply(possible.get(), registry);
            assertEquals(expected, actual, error);
        }
    }

    private void assertForRemovedKey(Key actual) {
        assertEquals(Key.getDefaultInstance(), actual, "Does not equal to a removed key");
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            LOG.info("Info for '{}': {}", () -> token, response.getTokenGetInfo()::getTokenInfo);
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
        return getTokenInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getTokenInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asTokenId(token, spec);
        TokenGetInfoQuery getTokenQuery = TokenGetInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setToken(id)
                .build();
        return Query.newBuilder().setTokenGetInfo(getTokenQuery).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this).add("token", token);
    }
}
