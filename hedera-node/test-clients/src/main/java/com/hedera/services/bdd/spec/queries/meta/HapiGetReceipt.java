// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.meta;

import static com.hedera.services.bdd.spec.queries.QueryUtils.txnReceiptQueryFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetReceipt extends HapiQueryOp<HapiGetReceipt> {
    static final Logger log = LogManager.getLogger(HapiGetReceipt.class);

    String txn;
    boolean forgetOp = false;
    boolean requestDuplicates = false;
    boolean useDefaultTxnId = false;
    boolean getChildReceipts = false;
    TransactionID defaultTxnId = TransactionID.getDefaultInstance();
    Optional<String> expectedSchedule = Optional.empty();
    Optional<String> expectedScheduledTxnId = Optional.empty();
    Optional<TransactionID> explicitTxnId = Optional.empty();
    Optional<ResponseCodeEnum> expectedPriorityStatus = Optional.empty();

    @Nullable
    Set<ResponseCodeEnum> expectedPriorityStatuses = null;

    Optional<ResponseCodeEnum[]> expectedDuplicateStatuses = Optional.empty();
    Optional<Integer> hasChildAutoAccountCreations = Optional.empty();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TransactionGetReceipt;
    }

    @Override
    protected HapiGetReceipt self() {
        return this;
    }

    public HapiGetReceipt(String txn) {
        this.txn = txn;
    }

    public HapiGetReceipt(TransactionID txnId) {
        explicitTxnId = Optional.of(txnId);
    }

    public HapiGetReceipt forgetOp() {
        forgetOp = true;
        return this;
    }

    public HapiGetReceipt andAnyDuplicates() {
        requestDuplicates = true;
        return this;
    }

    public HapiGetReceipt andAnyChildReceipts() {
        getChildReceipts = true;
        return this;
    }

    public HapiGetReceipt useDefaultTxnId() {
        useDefaultTxnId = true;
        return this;
    }

    public HapiGetReceipt hasChildAutoAccountCreations(int count) {
        hasChildAutoAccountCreations = Optional.of(count);
        return this;
    }

    public HapiGetReceipt hasPriorityStatus(ResponseCodeEnum status) {
        expectedPriorityStatus = Optional.of(status);
        return this;
    }

    public HapiGetReceipt hasPriorityStatusFrom(ResponseCodeEnum... statuses) {
        if (statuses.length == 0) {
            throw new IllegalArgumentException("Must specify at least one status");
        }
        expectedPriorityStatuses = EnumSet.copyOf(Arrays.asList(statuses));
        return this;
    }

    public HapiGetReceipt hasDuplicateStatuses(ResponseCodeEnum... statuses) {
        expectedDuplicateStatuses = Optional.of(statuses);
        return this;
    }

    public HapiGetReceipt hasScheduledTxnId(String name) {
        expectedScheduledTxnId = Optional.of(HapiScheduleCreate.correspondingScheduledTxnId(name));
        return this;
    }

    public HapiGetReceipt hasSchedule(String name) {
        expectedSchedule = Optional.of(name);
        return this;
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        childReceipts = response.getTransactionGetReceipt().getChildTransactionReceiptsList();
        final var duplicateReceipts = response.getTransactionGetReceipt().getDuplicateTransactionReceiptsList();
        if (verboseLoggingOn) {
            String message = String.format(
                    "Receipt: %s", response.getTransactionGetReceipt().getReceipt());
            log.info(message);
            String message2 = String.format(
                    "%s  And %d child receipts%s: %s",
                    spec.logPrefix(), childReceipts.size(), childReceipts.size() > 1 ? "s" : "", childReceipts);

            log.info(message2);

            String message3 = String.format(
                    "%s  And %d duplicate receipts%s: %s",
                    spec.logPrefix(),
                    duplicateReceipts.size(),
                    duplicateReceipts.size() > 1 ? "s" : "",
                    duplicateReceipts);

            log.info(message3);
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
        if (forgetOp) {
            return Query.getDefaultInstance();
        }
        return txnReceiptQueryFor(
                explicitTxnId.orElseGet(
                        () -> useDefaultTxnId ? defaultTxnId : spec.registry().getTxnId(txn)),
                requestDuplicates,
                getChildReceipts);
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) {
        var receipt = response.getTransactionGetReceipt().getReceipt();
        ResponseCodeEnum actualStatus = receipt.getStatus();
        if (expectedPriorityStatuses != null && expectedPriorityStatuses.contains(actualStatus)) {
            expectedPriorityStatus = Optional.of(actualStatus);
        }
        if (expectedPriorityStatus.isPresent()) {
            assertEquals(expectedPriorityStatus.get(), actualStatus);
        }
        if (expectedDuplicateStatuses.isPresent()) {
            var duplicates = response.getTransactionGetReceipt().getDuplicateTransactionReceiptsList().stream()
                    .map(TransactionReceipt::getStatus)
                    .toArray(ResponseCodeEnum[]::new);
            Assertions.assertArrayEquals(expectedDuplicateStatuses.get(), duplicates);
        }
        if (expectedScheduledTxnId.isPresent()) {
            var expected = spec.registry().getTxnId(expectedScheduledTxnId.get());
            var actual = response.getTransactionGetReceipt().getReceipt().getScheduledTransactionID();
            assertEquals(expected, actual, "Wrong scheduled transaction id!");
        }
        if (expectedSchedule.isPresent()) {
            var schedule = TxnUtils.asScheduleId(expectedSchedule.get(), spec);
            assertEquals(schedule, receipt.getScheduleID(), "Wrong/missing schedule id!");
        }
        if (hasChildAutoAccountCreations.isPresent()) {
            int count = hasChildAutoAccountCreations.get();
            for (var childReceipt : childReceipts) {
                if (childReceipt.hasAccountID()) {
                    count--;
                }
            }
            assertEquals(0, count);
        }
    }

    @Override
    protected boolean needsPayment() {
        return false;
    }

    @Override
    protected long costOnlyNodePayment(HapiSpec spec) {
        return 0L;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("txn", txn).add("explicit Txn :", explicitTxnId);
    }
}
