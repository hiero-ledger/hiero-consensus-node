// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.file;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiGetFileInfo extends HapiQueryOp<HapiGetFileInfo> {
    private static final Logger LOG = LogManager.getLogger(HapiGetFileInfo.class);

    private static final String MISSING_FILE = "<n/a>";

    private String file = MISSING_FILE;

    private boolean immutable = false;
    private Optional<String> saveFileInfoToReg = Optional.empty();
    private Optional<Boolean> expectedDeleted = Optional.empty();
    private Optional<String> expectedWacl = Optional.empty();
    private Optional<String> expectedMemo = Optional.empty();

    @SuppressWarnings("java:S1068")
    private Optional<String> expectedKeyRepr = Optional.empty();

    private Optional<LongPredicate> expiryTest = Optional.empty();
    private Optional<Supplier<String>> fileSupplier = Optional.empty();
    private Optional<Consumer<String>> keyReprObserver = Optional.empty();

    private FileID fileId;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.FileGetInfo;
    }

    @Override
    protected HapiGetFileInfo self() {
        return this;
    }

    public HapiGetFileInfo isUnmodifiable() {
        immutable = true;
        return this;
    }

    public HapiGetFileInfo hasKeyReprTo(String repr) {
        expectedKeyRepr = Optional.of(repr);
        return this;
    }

    public HapiGetFileInfo exposingKeyReprTo(Consumer<String> obs) {
        keyReprObserver = Optional.of(obs);
        return this;
    }

    public HapiGetFileInfo hasMemo(String v) {
        expectedMemo = Optional.of(v);
        return this;
    }

    public HapiGetFileInfo hasExpiry(LongSupplier expected) {
        expiryTest = Optional.of(v -> v == expected.getAsLong());
        return this;
    }

    public HapiGetFileInfo hasExpiryPassing(LongPredicate test) {
        expiryTest = Optional.of(test);
        return this;
    }

    public HapiGetFileInfo hasDeleted(boolean expected) {
        expectedDeleted = Optional.of(expected);
        return this;
    }

    public HapiGetFileInfo hasWacl(String expected) {
        expectedWacl = Optional.of(expected);
        return this;
    }

    public HapiGetFileInfo saveToRegistry(String name) {
        saveFileInfoToReg = Optional.of(name);
        return this;
    }

    public HapiGetFileInfo(String file) {
        this.file = file;
    }

    public HapiGetFileInfo(Supplier<String> supplier) {
        fileSupplier = Optional.of(supplier);
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            LOG.info("Info for file '{}': {}", file, response.getFileGetInfo());
        }
        if (saveFileInfoToReg.isPresent()) {
            spec.registry()
                    .saveFileInfo(
                            saveFileInfoToReg.get(), response.getFileGetInfo().getFileInfo());
        }
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        var info = response.getFileGetInfo().getFileInfo();

        Assertions.assertEquals(TxnUtils.asFileId(file, spec), info.getFileID(), "Wrong file id!");
        keyReprObserver.ifPresent(obs -> obs.accept(info.getKeys().toString().replaceAll("\\s", "")));

        if (immutable) {
            Assertions.assertFalse(info.hasKeys(), "Should have no WACL, expected immutable!");
        }
        expectedWacl.ifPresent(
                k -> Assertions.assertEquals(spec.registry().getKey(k).getKeyList(), info.getKeys(), "Bad WACL!"));
        expectedDeleted.ifPresent(f -> Assertions.assertEquals(f, info.getDeleted(), "Bad deletion status!"));
        long actual = info.getExpirationTime().getSeconds();
        expiryTest.ifPresent(
                p -> Assertions.assertTrue(p.test(actual), String.format("Expiry of %d was not as expected!", actual)));
        expectedMemo.ifPresent(e -> Assertions.assertEquals(e, info.getMemo()));
        expectedLedgerId.ifPresent(id -> Assertions.assertEquals(id, info.getLedgerId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getFileInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getFileInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        file = fileSupplier.isPresent() ? fileSupplier.get().get() : file;
        var id = TxnUtils.asFileId(file, spec);
        fileId = id;
        FileGetInfoQuery infoQuery = FileGetInfoQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setFileID(id)
                .build();
        return Query.newBuilder().setFileGetInfo(infoQuery).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("file", file).add("fileId", fileId);
    }
}
