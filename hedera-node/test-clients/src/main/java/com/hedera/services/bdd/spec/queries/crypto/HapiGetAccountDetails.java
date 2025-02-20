// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.crypto;

import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.GetAccountDetailsQuery;
import com.hederahashgraph.api.proto.java.GetAccountDetailsResponse.AccountDetails;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetAccountDetails extends HapiQueryOp<HapiGetAccountDetails> {
    private static final Logger log = LogManager.getLogger(HapiGetAccountDetails.class);

    private String account;
    private String aliasKeySource = null;
    private Optional<String> registryEntry = Optional.empty();
    private List<String> absentRelationships = new ArrayList<>();
    private List<ExpectedTokenRel> relationships = new ArrayList<>();
    Optional<AccountDetailsAsserts> expectations = Optional.empty();
    Optional<BiConsumer<AccountDetails, Logger>> customLog = Optional.empty();
    Optional<LongConsumer> exposingExpiryTo = Optional.empty();
    Optional<LongConsumer> exposingBalanceTo = Optional.empty();
    Optional<Long> ownedNfts = Optional.empty();
    Optional<Integer> maxAutomaticAssociations = Optional.empty();
    Optional<Integer> alreadyUsedAutomaticAssociations = Optional.empty();
    private Optional<Consumer<AccountID>> idObserver = Optional.empty();
    private Optional<Integer> tokenAssociationsCount = Optional.empty();
    private boolean assertAliasKeyMatches = false;
    private boolean assertAccountIDIsNotAlias = false;
    private ReferenceType referenceType;

    public HapiGetAccountDetails(String account) {
        this(account, ReferenceType.REGISTRY_NAME);
    }

    public HapiGetAccountDetails(String reference, ReferenceType type) {
        this.referenceType = type;
        if (type == ReferenceType.ALIAS_KEY_NAME) {
            aliasKeySource = reference;
        } else {
            account = reference;
        }
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.GetAccountDetails;
    }

    public HapiGetAccountDetails has(AccountDetailsAsserts provider) {
        expectations = Optional.of(provider);
        return this;
    }

    public HapiGetAccountDetails hasExpectedAliasKey() {
        assertAliasKeyMatches = true;
        return this;
    }

    public HapiGetAccountDetails hasExpectedAccountID() {
        assertAccountIDIsNotAlias = true;
        return this;
    }

    public HapiGetAccountDetails plusCustomLog(BiConsumer<AccountDetails, Logger> custom) {
        customLog = Optional.of(custom);
        return this;
    }

    public HapiGetAccountDetails exposingExpiry(LongConsumer obs) {
        this.exposingExpiryTo = Optional.of(obs);
        return this;
    }

    public HapiGetAccountDetails exposingIdTo(Consumer<AccountID> obs) {
        this.idObserver = Optional.of(obs);
        return this;
    }

    public HapiGetAccountDetails exposingBalance(LongConsumer obs) {
        this.exposingBalanceTo = Optional.of(obs);
        return this;
    }

    public HapiGetAccountDetails savingSnapshot(String registryEntry) {
        this.registryEntry = Optional.of(registryEntry);
        return this;
    }

    public HapiGetAccountDetails hasToken(ExpectedTokenRel relationship) {
        relationships.add(relationship);
        return this;
    }

    public HapiGetAccountDetails hasNoTokenRelationship(String token) {
        absentRelationships.add(token);
        return this;
    }

    public HapiGetAccountDetails hasTokenRelationShipCount(int count) {
        tokenAssociationsCount = Optional.of(count);
        return this;
    }

    public HapiGetAccountDetails hasOwnedNfts(long ownedNftsLen) {
        this.ownedNfts = Optional.of(ownedNftsLen);
        return this;
    }

    public HapiGetAccountDetails hasMaxAutomaticAssociations(int max) {
        this.maxAutomaticAssociations = Optional.of(max);
        return this;
    }

    public HapiGetAccountDetails hasAlreadyUsedAutomaticAssociations(int count) {
        this.alreadyUsedAutomaticAssociations = Optional.of(count);
        return this;
    }

    @Override
    protected HapiGetAccountDetails self() {
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        final var details = response.getAccountDetails().getAccountDetails();
        if (assertAliasKeyMatches) {
            Objects.requireNonNull(aliasKeySource);
            final var expected = spec.registry().getKey(aliasKeySource).toByteString();
            assertEquals(expected, details.getAlias());
        }
        if (assertAccountIDIsNotAlias) {
            Objects.requireNonNull(aliasKeySource);
            final var expectedKeyForAccount =
                    spec.registry().getKey(aliasKeySource).toByteString().toStringUtf8();
            final var expectedID = spec.registry().getAccountID(expectedKeyForAccount);
            Assertions.assertNotEquals(
                    details.getAlias(), details.getAccountId().getAccountNum());
            assertEquals(expectedID, details.getAccountId());
        }
        if (expectations.isPresent()) {
            ErroringAsserts<AccountDetails> asserts = expectations.get().assertsFor(spec);
            List<Throwable> errors = asserts.errorsIn(details);
            rethrowSummaryError(log, "Bad account details!", errors);
        }
        var actualTokenRels = details.getTokenRelationshipsList();
        ExpectedTokenRel.assertExpectedRels(account, relationships, actualTokenRels, spec);
        ExpectedTokenRel.assertNoUnexpectedRels(account, absentRelationships, actualTokenRels, spec);

        var actualOwnedNfts = details.getOwnedNfts();
        ownedNfts.ifPresent(nftsOwned -> assertEquals((long) nftsOwned, actualOwnedNfts));

        var actualMaxAutoAssociations = details.getMaxAutomaticTokenAssociations();
        maxAutomaticAssociations.ifPresent(
                maxAutoAssociations -> assertEquals((int) maxAutoAssociations, actualMaxAutoAssociations));
        alreadyUsedAutomaticAssociations.ifPresent(usedCount -> {
            int actualCount = 0;
            for (var rel : actualTokenRels) {
                if (rel.getAutomaticAssociation()) {
                    actualCount++;
                }
            }
            assertEquals(actualCount, usedCount);
        });
        expectedLedgerId.ifPresent(id -> assertEquals(id, details.getLedgerId()));

        tokenAssociationsCount.ifPresent(count -> assertEquals(count, details.getTokenRelationshipsCount()));
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var details = response.getAccountDetails();
        if (details.getHeader().getNodeTransactionPrecheckCode() == OK) {
            exposingExpiryTo.ifPresent(cb ->
                    cb.accept(details.getAccountDetails().getExpirationTime().getSeconds()));
            exposingBalanceTo.ifPresent(
                    cb -> cb.accept(details.getAccountDetails().getBalance()));
            idObserver.ifPresent(cb -> cb.accept(details.getAccountDetails().getAccountId()));
        }
        if (verboseLoggingOn) {
            String message = String.format(
                    "Details for '%s': %s", repr(), response.getAccountDetails().getAccountDetails());
            log.info(message);
        }
        if (customLog.isPresent()) {
            customLog.get().accept(response.getAccountDetails().getAccountDetails(), log);
        }
        if (registryEntry.isPresent()) {
            spec.registry()
                    .saveAccountDetails(
                            registryEntry.get(), response.getAccountDetails().getAccountDetails());
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
        AccountID target;
        if (referenceType == ReferenceType.ALIAS_KEY_NAME) {
            target = AccountID.newBuilder()
                    .setAlias(spec.registry().getKey(aliasKeySource).toByteString())
                    .build();
        } else {
            target = TxnUtils.asId(account, spec);
        }
        GetAccountDetailsQuery query = GetAccountDetailsQuery.newBuilder()
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

    private String repr() {
        if (referenceType == ReferenceType.REGISTRY_NAME) {
            return account;
        } else {
            return "KeyAlias(" + aliasKeySource + ")";
        }
    }
}
