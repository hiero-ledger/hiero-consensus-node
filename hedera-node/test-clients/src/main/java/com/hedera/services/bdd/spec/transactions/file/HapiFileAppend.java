// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.file;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.currExpiry;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.file.FileAppendMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class HapiFileAppend extends HapiTxnOp<HapiFileAppend> {
    private final String file;
    private Optional<byte[]> contents = Optional.empty();
    private Optional<Supplier<byte[]>> contentsSupplier = Optional.empty();
    private Optional<String> path = Optional.empty();

    private Optional<Consumer<FileID>> preAppendCb = Optional.empty();
    private Optional<Consumer<ResponseCodeEnum>> postAppendCb = Optional.empty();

    @Nullable
    private UploadProgress uploadProgress;

    private int appendNum;

    public HapiFileAppend(final String file) {
        this.file = file;
    }

    public HapiFileAppend trackingProgressIn(final UploadProgress uploadProgress, final int appendNum) {
        this.uploadProgress = uploadProgress;
        this.appendNum = appendNum;
        return this;
    }

    public HapiFileAppend alertingPre(final Consumer<FileID> preCb) {
        preAppendCb = Optional.of(preCb);
        return this;
    }

    public HapiFileAppend alertingPost(final Consumer<ResponseCodeEnum> postCb) {
        postAppendCb = Optional.of(postCb);
        return this;
    }

    public HapiFileAppend content(final byte[] data) {
        contents = Optional.of(data);
        return this;
    }

    public HapiFileAppend content(final String data) {
        contents = Optional.of(data.getBytes());
        return this;
    }

    public HapiFileAppend path(final String to) {
        path = Optional.of(to);
        return this;
    }

    public HapiFileAppend contentFrom(final Supplier<byte[]> more) {
        contentsSupplier = Optional.of(more);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.FileAppend;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        if (contentsSupplier.isPresent()) {
            contents = Optional.of(contentsSupplier.get().get());
        } else if (path.isPresent()) {
            contents = Optional.of(Files.toByteArray(new File(path.get())));
        }
        final var fid = TxnUtils.asFileId(file, spec);
        final FileAppendTransactionBody opBody = spec.txns()
                .<FileAppendTransactionBody, FileAppendTransactionBody.Builder>body(
                        FileAppendTransactionBody.class, builder -> {
                            builder.setFileID(fid);
                            contents.ifPresent(b -> builder.setContents(ByteString.copyFrom(b)));
                        });
        preAppendCb.ifPresent(cb -> cb.accept(fid));
        return b -> b.setFileAppend(opBody);
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        final var expiry =
                payer.isPresent() ? currExpiry(file, spec, payerToUse(payer.get(), spec)) : currExpiry(file, spec);
        final FeeCalculator.ActivityMetrics metricsCalc =
                (txBody, sigUsage) -> usageEstimate(txBody, sigUsage, expiry.getSeconds());
        return spec.fees().forActivityBasedOp(HederaFunctionality.FileAppend, metricsCalc, txn, numPayerKeys);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(file));
    }

    @Override
    protected void updateStateOf(final HapiSpec spec) throws Throwable {
        postAppendCb.ifPresent(cb -> cb.accept(actualStatus));
        if (actualStatus == SUCCESS && uploadProgress != null) {
            uploadProgress.markFinished(appendNum);
        }
    }

    @Override
    protected HapiFileAppend self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("fileName", file);
    }

    private String payerToUse(final String designated, final HapiSpec spec) {
        return isPrivileged(designated, spec) ? spec.setup().genesisAccountName() : designated;
    }

    private boolean isPrivileged(final String account, final HapiSpec spec) {
        return account.equals(spec.setup().addressBookControlName())
                || account.equals(spec.setup().exchangeRatesControlName())
                || account.equals(spec.setup().feeScheduleControlName())
                || account.equals(spec.setup().strongControlName());
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo, final long expiry) {
        final var op = txn.getFileAppend();
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var effectiveNow =
                txn.getTransactionID().getTransactionValidStart().getSeconds();
        final var opMeta = new FileAppendMeta(op.getContents().size(), expiry - effectiveNow);

        final var accumulator = new UsageAccumulator();
        fileOpsUsage.fileAppendUsage(suFrom(svo), opMeta, baseMeta, accumulator);

        return AdapterUtils.feeDataFrom(accumulator);
    }
}
