// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.queries.QueryUtils.reflectForCost;
import static com.hedera.services.bdd.spec.queries.QueryUtils.reflectForPrecheck;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTransferList;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.txnToString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;
import static java.lang.Thread.sleep;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.exceptions.HapiQueryCheckStateException;
import com.hedera.services.bdd.spec.exceptions.HapiQueryPrecheckStateException;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.SigMapGenerator;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.mod.QueryMutation;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class HapiQueryOp<T extends HapiQueryOp<T>> extends HapiSpecOperation {
    private static final Logger log = LogManager.getLogger(HapiQueryOp.class);

    @Nullable
    private QueryMutation queryMutation = null;

    @Nullable
    private LongConsumer nodePaymentObserver = null;

    // The query sent to the network
    protected Query query = null;
    // The response received from the network
    protected Response response = null;
    protected List<TransactionRecord> childRecords = null;
    protected List<TransactionReceipt> childReceipts = null;
    protected ResponseCodeEnum actualPrecheck = UNKNOWN;
    private Optional<ResponseCodeEnum> answerOnlyPrecheck = Optional.empty();
    private Optional<Function<HapiSpec, Long>> nodePaymentFn = Optional.empty();
    private Optional<EnumSet<ResponseCodeEnum>> permissibleAnswerOnlyPrechecks = Optional.empty();
    private Optional<EnumSet<ResponseCodeEnum>> permissibleCostAnswerPrechecks = Optional.empty();
    /** if response code in the set then allow to resubmit transaction */
    protected Optional<EnumSet<ResponseCodeEnum>> answerOnlyRetryPrechecks = Optional.empty();

    private boolean asNodeOperator = false;

    private ResponseCodeEnum expectedCostAnswerPrecheck() {
        return costAnswerPrecheck.orElse(OK);
    }

    private ResponseCodeEnum expectedAnswerOnlyPrecheck() {
        return answerOnlyPrecheck.orElse(OK);
    }

    protected Optional<Long> nodePayment = Optional.empty();
    protected Optional<ResponseCodeEnum> costAnswerPrecheck = Optional.empty();
    protected Optional<HapiCryptoTransfer> explicitPayment = Optional.empty();

    protected abstract boolean needsPayment();

    /**
     * Returns the query to be sent to the network in the context of the given spec with the
     * given payment, for the given response type.
     *
     * @param spec the context in which the query is to be sent
     * @param payment the payment to be used for the query
     * @param responseType the type of response the query should elicit
     * @return the query to be sent to the network
     */
    protected abstract Query queryFor(
            @NonNull HapiSpec spec, @NonNull Transaction payment, @NonNull ResponseType responseType);

    /**
     * Called immediately before the {@link ResponseType#ANSWER_ONLY} query is sent.
     */
    protected void beforeAnswerOnlyQuery() {}

    /**
     * Called immediately after the {@link ResponseType#ANSWER_ONLY} response is received
     * to give the subclass a chance to process the response.
     */
    protected abstract void processAnswerOnlyResponse(@NonNull HapiSpec spec);

    /**
     * Returns the modified version of the query in the context of the given spec, if a mutation
     * is present; otherwise, returns the query as is.
     *
     * @param query the query to be modified
     * @param spec the spec in which the query is to be modified
     * @return the modified query
     */
    protected Query maybeModified(@NonNull final Query query, @NonNull final HapiSpec spec) {
        // Save the unmodified version of the query
        this.query = query;
        return queryMutation != null ? queryMutation.apply(query, spec) : query;
    }

    public T withUnknownFieldIn(final UnknownFieldLocation location) {
        unknownFieldLocation = location;
        return self();
    }

    protected long costOnlyNodePayment(HapiSpec spec) throws Throwable {
        return 0L;
    }

    public Query getQuery() {
        return query;
    }

    public Response getResponse() {
        return response;
    }

    protected long costFrom(Response response) throws Throwable {
        ResponseCodeEnum actualPrecheck = reflectForPrecheck(response);
        if (permissibleCostAnswerPrechecks.isPresent()) {
            if (permissibleCostAnswerPrechecks.get().contains(actualPrecheck)) {
                costAnswerPrecheck = Optional.of(actualPrecheck);
            } else {
                String errMsg = String.format(
                        "Cost-answer precheck was %s, not one of %s!",
                        actualPrecheck, permissibleCostAnswerPrechecks.get());
                if (!loggingOff) {
                    log.error(errMsg);
                }
                throw new HapiQueryCheckStateException(errMsg);
            }
        } else {
            if (expectedCostAnswerPrecheck() != actualPrecheck) {
                String errMsg = String.format(
                        "Bad costAnswerPrecheck! expected %s, actual %s", expectedCostAnswerPrecheck(), actualPrecheck);
                if (!loggingOff) {
                    log.error(errMsg);
                }
                throw new HapiQueryCheckStateException(errMsg);
            }
        }
        return reflectForCost(response);
    }

    protected abstract T self();

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        configureTlsFor(spec);

        Transaction payment = Transaction.getDefaultInstance();
        int retryCount = 1;
        while (true) {
            /* Note that HapiQueryOp#fittedPayment makes a COST_ANSWER query if necessary. */
            if (needsPayment()) {
                payment = fittedPayment(spec);
            }

            /* If the COST_ANSWER query was expected to fail, we will not do anything else for this query. */
            if (needsPayment() && nodePayment.isEmpty() && expectedCostAnswerPrecheck() != OK) {
                return false;
            }

            if (needsPayment() && !loggingOff) {
                String message;
                if (asNodeOperator) {
                    message = String.format(
                            "%sNode operator sending %s with %s", spec.logPrefix(), this, txnToString(payment));
                } else {
                    message = String.format("%sPaying for %s with %s", spec.logPrefix(), this, txnToString(payment));
                }

                log.info(message);
            }
            query = maybeModified(queryFor(spec, payment, ResponseType.ANSWER_ONLY), spec);
            beforeAnswerOnlyQuery();
            response = spec.targetNetworkOrThrow().send(query, type(), targetNodeFor(spec), asNodeOperator);
            processAnswerOnlyResponse(spec);

            actualPrecheck = reflectForPrecheck(response);

            // no need to retry if the precheck status is the expected one
            if (actualPrecheck == expectedAnswerOnlyPrecheck()) {
                break;
            }

            if (answerOnlyRetryPrechecks.isPresent()
                    && answerOnlyRetryPrechecks.get().contains(actualPrecheck)
                    && isWithInRetryLimit(retryCount)) {
                retryCount++;
                log.trace("{}retry count: {}", spec.logPrefix(), retryCount);
                sleep(10);
            } else {
                break;
            }
        }
        if (permissibleAnswerOnlyPrechecks.isPresent()) {
            if (permissibleAnswerOnlyPrechecks.get().contains(actualPrecheck)) {
                answerOnlyPrecheck = Optional.of(actualPrecheck);
            } else {
                final String errMsg = String.format(
                        "Answer-only precheck was %s, not one of %s!",
                        actualPrecheck, permissibleAnswerOnlyPrechecks.get());
                if (!loggingOff) {
                    log.error(errMsg);
                }
                throw new HapiQueryPrecheckStateException(errMsg);
            }
        } else {
            if (expectedAnswerOnlyPrecheck() != actualPrecheck) {
                final String errMsg = String.format(
                        "Bad answerOnlyPrecheck! expected %s, actual %s", expectedAnswerOnlyPrecheck(), actualPrecheck);
                if (!loggingOff) {
                    log.error(errMsg);
                }
                throw new HapiQueryPrecheckStateException(errMsg);
            }
        }
        if (expectedCostAnswerPrecheck() != OK || expectedAnswerOnlyPrecheck() != OK) {
            return false;
        }
        txnSubmitted = payment;
        return true;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.CryptoTransfer,
                        (_txn, _svo) -> usageEstimate(_txn, _svo, spec.fees().tokenTransferUsageMultiplier()),
                        txn,
                        numPayerKeys);
    }

    private FeeData usageEstimate(TransactionBody txn, SigValueObj svo, int multiplier) {
        return HapiCryptoTransfer.usageEstimate(txn, svo, multiplier);
    }

    private Transaction fittedPayment(HapiSpec spec) throws Throwable {
        if (explicitPayment.isPresent()) {
            return explicitPayment.get().signedTxnFor(spec);
        } else if (nodePaymentFn.isPresent()) {
            return finalizedTxn(spec, opDef(spec, nodePaymentFn.get().apply(spec)));
        } else if (nodePayment.isPresent()) {
            return finalizedTxn(spec, opDef(spec, nodePayment.get()));
        } else {
            long initNodePayment = costOnlyNodePayment(spec);
            Transaction payment = finalizedTxn(spec, opDef(spec, initNodePayment), true);
            if (!loggingOff) {
                String message;
                if (asNodeOperator) {
                    message = String.format(
                            "%sNode operator sending COST_ANSWER query for %s with %s",
                            spec.logPrefix(), this, txnToString(payment));
                } else {
                    message = String.format(
                            "%sPaying for COST_ANSWER of %s with %s", spec.logPrefix(), this, txnToString(payment));
                }
                log.info(message);
            }
            query = maybeModified(queryFor(spec, payment, ResponseType.COST_ANSWER), spec);
            response = spec.targetNetworkOrThrow().send(query, type(), targetNodeFor(spec), asNodeOperator);
            final var realNodePayment = costFrom(response);
            Optional.ofNullable(nodePaymentObserver).ifPresent(observer -> observer.accept(realNodePayment));
            if (expectedCostAnswerPrecheck() != OK) {
                return Transaction.getDefaultInstance();
            }
            txnSubmitted = payment;
            if (!loggingOff) {
                final String message = String.format(
                        "%s--> Node payment for %s is %s tinyBars.", spec.logPrefix(), this, realNodePayment);
                log.info(message);
            }
            return finalizedTxn(spec, opDef(spec, realNodePayment));
        }
    }

    private Consumer<TransactionBody.Builder> opDef(HapiSpec spec, long amount) throws Throwable {
        TransferList transfers = asTransferList(
                tinyBarsFromTo(amount, spec.registry().getAccountID(effectivePayer(spec)), targetNodeFor(spec)));
        CryptoTransferTransactionBody opBody = spec.txns()
                .<CryptoTransferTransactionBody, CryptoTransferTransactionBody.Builder>body(
                        CryptoTransferTransactionBody.class, b -> b.setTransfers(transfers));
        return b -> b.setCryptoTransfer(opBody);
    }

    public T hasCostAnswerPrecheck(ResponseCodeEnum precheck) {
        costAnswerPrecheck = Optional.of(precheck);
        return self();
    }

    public T hasCostAnswerPrecheckFrom(ResponseCodeEnum... prechecks) {
        permissibleCostAnswerPrechecks = Optional.of(EnumSet.copyOf(List.of(prechecks)));
        return self();
    }

    public T hasAnswerOnlyPrecheck(ResponseCodeEnum precheck) {
        answerOnlyPrecheck = Optional.of(precheck);
        return self();
    }

    public T hasAnswerOnlyPrecheckFrom(ResponseCodeEnum... prechecks) {
        permissibleAnswerOnlyPrechecks = Optional.of(EnumSet.copyOf(List.of(prechecks)));
        return self();
    }

    public T hasRetryAnswerOnlyPrecheck(ResponseCodeEnum... statuses) {
        answerOnlyRetryPrechecks = Optional.of(EnumSet.copyOf(List.of(statuses)));
        return self();
    }

    public T setRetryLimit(int limit) {
        retryLimits = Optional.of(limit);
        return self();
    }

    public T nodePayment(Function<HapiSpec, Long> fn) {
        nodePaymentFn = Optional.of(fn);
        return self();
    }

    public T nodePayment(long amount) {
        nodePayment = Optional.of(amount);
        return self();
    }

    public T via(String name) {
        txnName = name;
        shouldRegisterTxn = true;
        return self();
    }

    public T fee(long amount) {
        if (amount >= 0) {
            fee = Optional.of(amount);
        }
        return self();
    }

    public T logged() {
        verboseLoggingOn = true;
        return self();
    }

    public T payingWith(String name) {
        payer = Optional.of(name);
        return self();
    }

    public T signedBy(String... keys) {
        signers = Optional.of(Stream.of(keys)
                .<Function<HapiSpec, Key>>map(k -> spec -> spec.registry().getKey(k))
                .collect(toList()));
        return self();
    }

    public T record(Boolean isGenerated) {
        genRecord = Optional.of(isGenerated);
        return self();
    }

    public T sigMapPrefixes(SigMapGenerator gen) {
        sigMapGen = Optional.of(gen);
        return self();
    }

    public T sigControl(ControlForKey... overrides) {
        controlOverrides = Optional.of(overrides);
        return self();
    }

    public T numPayerSigs(int hardcoded) {
        this.hardcodedNumPayerKeys = Optional.of(hardcoded);
        return self();
    }

    public T hasEncodedLedgerId(ByteString ledgerId) {
        this.expectedLedgerId = Optional.of(ledgerId);
        return self();
    }

    public T noLogging() {
        loggingOff = true;
        return self();
    }

    public T logging() {
        loggingOff = false;
        return self();
    }

    public T withYahcliLogging() {
        yahcliLogger = true;
        return self();
    }

    public T useEmptyTxnAsCostPayment() {
        useDefaultTxnAsCostAnswerPayment = true;
        return self();
    }

    public T useEmptyTxnAsAnswerPayment() {
        useDefaultTxnAsAnswerOnlyPayment = true;
        return self();
    }

    public T setNode(String accountNum) {
        node = Optional.of(accountNum);
        return self();
    }

    public T setNodeFrom(Supplier<String> accountSupplier) {
        nodeSupplier = Optional.of(() -> asAccount(accountSupplier.get()));
        return self();
    }

    public T withPayment(HapiCryptoTransfer txn) {
        explicitPayment = Optional.of(txn);
        return self();
    }

    public T withQueryMutation(@Nullable final QueryMutation queryMutation) {
        this.queryMutation = queryMutation;
        return self();
    }

    public T exposingNodePaymentTo(@NonNull final LongConsumer observer) {
        nodePaymentObserver = requireNonNull(observer);
        return self();
    }

    public T asNodeOperator() {
        asNodeOperator = true;
        return self();
    }
}
