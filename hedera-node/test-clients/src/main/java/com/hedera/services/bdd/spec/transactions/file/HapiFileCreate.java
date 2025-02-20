// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.file;

import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.transactions.TxnFactory.defaultExpiryNowFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bannerWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnFactory;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiFileCreate extends HapiTxnOp<HapiFileCreate> {
    static final Logger LOG = LogManager.getLogger(HapiFileCreate.class);

    private Key waclKey;
    private final String fileName;
    private boolean immutable = false;
    private boolean advertiseCreation = false;
    OptionalLong expiry = OptionalLong.empty();
    OptionalLong lifetime = OptionalLong.empty();
    Optional<String> contentsPath = Optional.empty();
    Optional<byte[]> contents = Optional.empty();
    Optional<String> keyName = Optional.empty();
    Optional<SigControl> waclControl = Optional.empty();
    Optional<LongConsumer> newNumObserver = Optional.empty();
    AtomicReference<Timestamp> expiryUsed = new AtomicReference<>();
    Optional<Function<HapiSpec, String>> contentsPathFn = Optional.empty();

    public HapiFileCreate(String fileName) {
        this.fileName = fileName;
    }

    public HapiFileCreate exposingNumTo(LongConsumer obs) {
        newNumObserver = Optional.of(obs);
        return this;
    }

    public HapiFileCreate advertisingCreation() {
        advertiseCreation = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.FileCreate;
    }

    @Override
    protected Key lookupKey(HapiSpec spec, String name) {
        return name.equals(fileName) ? waclKey : spec.registry().getKey(name);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        if (immutable) {
            return super.defaultSigners();
        } else {
            return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)), ignore -> waclKey);
        }
    }

    public HapiFileCreate unmodifiable() {
        immutable = true;
        return this;
    }

    public HapiFileCreate expiry(long at) {
        this.expiry = OptionalLong.of(at);
        return this;
    }

    public HapiFileCreate lifetime(long secs) {
        this.lifetime = OptionalLong.of(secs);
        return this;
    }

    public HapiFileCreate entityMemo(String s) {
        memo = Optional.of(s);
        return this;
    }

    public HapiFileCreate key(String keyName) {
        this.keyName = Optional.ofNullable(keyName);
        return this;
    }

    public HapiFileCreate waclShape(SigControl shape) {
        waclControl = Optional.of(shape);
        return this;
    }

    public HapiFileCreate contents(byte[] data) {
        contents = Optional.of(data);
        return this;
    }

    public HapiFileCreate contents(String s) {
        contents = Optional.of(s.getBytes());
        return this;
    }

    public HapiFileCreate fromResource(String name) {
        var baos = new ByteArrayOutputStream();
        try {
            HapiFileCreate.class.getClassLoader().getResourceAsStream(name).transferTo(baos);
            baos.close();
            contents = Optional.of(baos.toByteArray());
        } catch (IOException e) {
            LOG.warn("{} failed to read bytes from resource '{}'!", this, name, e);
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    public HapiFileCreate path(Function<HapiSpec, String> pathFn) {
        contentsPathFn = Optional.of(pathFn);
        return this;
    }

    public HapiFileCreate path(String path) {
        try {
            contentsPath = Optional.of(path);
            contents = Optional.of(Files.toByteArray(new File(path)));
        } catch (Exception t) {
            LOG.warn("{} failed to read bytes from '{}'!", this, path, t);
        }
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        if (!immutable) {
            generateWaclKey(spec);
        }
        if (contentsPathFn.isPresent()) {
            var loc = contentsPathFn.get().apply(spec);
            contents = Optional.of(Files.toByteArray(new File(loc)));
        }
        FileCreateTransactionBody opBody = spec.txns()
                .<FileCreateTransactionBody, FileCreateTransactionBody.Builder>body(
                        FileCreateTransactionBody.class, builder -> {
                            if (!immutable) {
                                builder.setKeys(waclKey.getKeyList());
                            }
                            memo.ifPresent(builder::setMemo);
                            contents.ifPresent(b -> builder.setContents(ByteString.copyFrom(b)));
                            lifetime.ifPresent(s -> builder.setExpirationTime(TxnFactory.expiryNowFor(spec, s)));
                            if (lifetime.isEmpty()) {
                                expiry.ifPresentOrElse(
                                        t -> builder.setExpirationTime(Timestamp.newBuilder()
                                                .setSeconds(t)
                                                .build()),
                                        () -> builder.setExpirationTime(defaultExpiryNowFor(spec)));
                            }
                        });
        return b -> {
            expiryUsed.set(opBody.getExpirationTime());
            b.setFileCreate(opBody);
        };
    }

    @SuppressWarnings("java:S5960")
    private void generateWaclKey(HapiSpec spec) {
        if (keyName.isPresent()) {
            waclKey = spec.registry().getKey(keyName.get());
            return;
        }
        if (waclControl.isPresent()) {
            final var control = waclControl.get();
            if (control.getNature() != SigControl.Nature.LIST) {
                throw new IllegalArgumentException("WACL must be a KeyList");
            }
            waclKey = spec.keys().generateSubjectTo(spec, control);
        } else {
            waclKey = spec.keys().generate(spec, KeyFactory.KeyType.LIST);
        }
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        final var newId = lastReceipt.getFileID();
        newNumObserver.ifPresent(obs -> obs.accept(newId.getFileNum()));

        if (!immutable) {
            spec.registry().saveKey(fileName, waclKey);
        }
        spec.registry().saveFileId(fileName, newId);
        spec.registry().saveTimestamp(fileName, expiryUsed.get());
        if (verboseLoggingOn) {
            LOG.info("Created file {} with ID {}.", fileName, lastReceipt.getFileID());
        }

        if (advertiseCreation) {
            String banner = "\n\n"
                    + bannerWith(String.format(
                            "Created file '%s' with id '%s'.", fileName, asFileString(lastReceipt.getFileID())));
            LOG.info(banner);
        }
    }

    @Override
    protected HapiFileCreate self() {
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerSigs) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.FileCreate, this::usageEstimate, txn, numPayerSigs);
    }

    private FeeData usageEstimate(TransactionBody txn, SigValueObj svo) {
        return fileOpsUsage.fileCreateUsage(txn, suFrom(svo));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper();
        contentsPath.ifPresent(p -> helper.add("path", p));
        Optional.ofNullable(lastReceipt)
                .ifPresent(receipt -> helper.add("created", receipt.getFileID().getFileNum()));
        return helper;
    }

    public long numOfCreatedFile() {
        return Optional.ofNullable(lastReceipt)
                .map(receipt -> receipt.getFileID().getFileNum())
                .orElse(-1L);
    }
}
