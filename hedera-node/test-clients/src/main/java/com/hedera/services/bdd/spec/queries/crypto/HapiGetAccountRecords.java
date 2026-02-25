// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.crypto;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.ensureDir;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.nonStakingRecordsFrom;

import com.google.common.base.MoreObjects;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.hedera.node.app.hapi.utils.fee.CryptoFeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.ErroringAssertsProvider;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsQuery;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetAccountRecords extends HapiQueryOp<HapiGetAccountRecords> {
    private static final Logger log = LogManager.getLogger(HapiGetAccountRecords.class);

    private String account;
    private Optional<String> snapshotDirPath = Optional.empty();
    private Optional<String> expectationsDirPath = Optional.empty();
    private Optional<ErroringAssertsProvider<List<TransactionRecord>>> expectation = Optional.empty();
    private Optional<BiConsumer<Logger, List<TransactionRecord>>> customLog = Optional.empty();

    @Nullable
    private Consumer<List<TransactionRecord>> observer = null;

    @Nullable
    private Consumer<List<TransactionRecord>> nonStakingObserver = null;

    public HapiGetAccountRecords has(ErroringAssertsProvider<List<TransactionRecord>> provider) {
        expectation = Optional.of(provider);
        return this;
    }

    public HapiGetAccountRecords withLogging(BiConsumer<Logger, List<TransactionRecord>> customLog) {
        verboseLoggingOn = true;
        this.customLog = Optional.of(customLog);
        return this;
    }

    public HapiGetAccountRecords savingTo(String dirPath) {
        snapshotDirPath = Optional.of(dirPath);
        return this;
    }

    public HapiGetAccountRecords checkingAgainst(String dirPath) {
        expectationsDirPath = Optional.of(dirPath);
        return this;
    }

    public HapiGetAccountRecords exposingTo(@NonNull final Consumer<List<TransactionRecord>> observer) {
        this.observer = observer;
        return this;
    }

    public HapiGetAccountRecords exposingNonStakingRecordsTo(
            @NonNull final Consumer<List<TransactionRecord>> observer) {
        this.nonStakingObserver = observer;
        return this;
    }

    public HapiGetAccountRecords(String account) {
        this.account = account;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.CryptoGetAccountRecords;
    }

    @Override
    protected HapiGetAccountRecords self() {
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        if (expectation.isPresent()) {
            List<TransactionRecord> actualRecords = response.getCryptoGetAccountRecords().getRecordsList().stream()
                    .filter(TxnUtils::isNotEndOfStakingPeriodRecord)
                    .toList();
            List<Throwable> errors = expectation.get().assertsFor(spec).errorsIn(actualRecords);
            rethrowSummaryError(log, "Bad account records!", errors);
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        List<TransactionRecord> records = response.getCryptoGetAccountRecords().getRecordsList();
        if (observer != null) {
            observer.accept(records);
        }
        if (nonStakingObserver != null) {
            final var nonStakingRecords = nonStakingRecordsFrom(records);
            nonStakingObserver.accept(nonStakingRecords);
        }
        if (verboseLoggingOn) {
            if (customLog.isPresent()) {
                customLog.get().accept(log, records);
            } else {
                log.info(records);
            }
        }
        if (snapshotDirPath.isPresent()) {
            saveSnapshots(spec, records);
        }
        if (expectationsDirPath.isPresent()) {
            checkExpectations(spec, records);
        }
    }

    private void checkExpectations(HapiSpec spec, List<TransactionRecord> records) {
        String specExpectationsDir = specScopedDir(spec, expectationsDirPath);
        try {
            String expectationsDir = specExpectationsDir + "/" + account;
            File countFile = new File(expectationsDir + "/n.txt");
            CharSource charSource = Files.asCharSource(countFile, Charset.forName("UTF-8"));
            int n = Integer.parseInt(charSource.readFirstLine());
            Assertions.assertEquals(n, records.size(), "Bad number of records - got " + records);
            for (int i = 0; i < n; i++) {
                File recordFile = new File(expectationsDir + "/record" + i + ".bin");
                ByteSource byteSource = Files.asByteSource(recordFile);
                TransactionRecord expected = TransactionRecord.parseFrom(byteSource.read());
                Assertions.assertEquals(expected, records.get(i), "Wrong record #" + i);
            }
        } catch (Exception e) {
            log.error("Something amiss with the expected records...", e);
            Assertions.fail("Impossible to meet expectations (on records)!");
        }
    }

    private String specScopedDir(HapiSpec spec, Optional<String> prefix) {
        return prefix.map(d -> d + "/" + spec.getName()).get();
    }

    private void saveSnapshots(HapiSpec spec, List<TransactionRecord> records) {
        String specSnapshotDir = specScopedDir(spec, snapshotDirPath);
        ensureDir(specSnapshotDir);
        String snapshotDir = specSnapshotDir + "/" + account;
        ensureDir(snapshotDir);

        try {
            File countFile = new File(snapshotDir + "/n.txt");
            CharSink charSink = Files.asCharSink(countFile, Charset.forName("UTF-8"));
            int n = records.size();
            charSink.write("" + n);

            for (int i = 0; i < n; i++) {
                File recordFile = new File(snapshotDir + "/record" + i + ".bin");
                ByteSink byteSink = Files.asByteSink(recordFile);
                byteSink.write(records.get(i).toByteArray());
            }
            String message = String.format("Saved %d records to %s", n, snapshotDir);
            log.info(message);
        } catch (Exception e) {
            log.error("Couldn't save record snapshots!", e);
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
        return getRecordsQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getRecordsQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asId(account, spec);
        CryptoGetAccountRecordsQuery query = CryptoGetAccountRecordsQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setAccountID(id)
                .build();
        return Query.newBuilder().setCryptoGetAccountRecords(query).build();
    }

    @Override
    protected long costOnlyNodePayment(HapiSpec spec) throws Throwable {
        return spec.fees()
                .forOp(
                        HederaFunctionality.CryptoGetAccountRecords,
                        CryptoFeeBuilder.getCostCryptoAccountRecordsQueryFeeMatrices());
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("account", account);
        Optional.ofNullable(response)
                .ifPresent(r -> helper.add(
                        "records",
                        r.getCryptoGetAccountRecords().getRecordsList().size()));
        return helper;
    }
}
