// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.file;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.TxnUsage.keySizeIfPresent;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASE_FILEINFO_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FileOpsUsage {
    private static final long LONG_BASIC_ENTITY_ID_SIZE = BASIC_ENTITY_ID_SIZE;

    static EstimatorFactory txnEstimateFactory = TxnUsageEstimator::new;
    static Function<ResponseType, QueryUsage> queryEstimateFactory = QueryUsage::new;

    /* { deleted } */
    private static final int NUM_FLAGS_IN_BASE_FILE_REPR = 1;
    /* { expiry } */
    private static final int NUM_LONG_FIELDS_IN_BASE_FILE_REPR = 1;

    static int bytesInBaseRepr() {
        return NUM_FLAGS_IN_BASE_FILE_REPR * BOOL_SIZE + NUM_LONG_FIELDS_IN_BASE_FILE_REPR * LONG_SIZE;
    }

    @Inject
    public FileOpsUsage() {
        // Default constructor
    }

    public void fileAppendUsage(
            final SigUsage sigUsage,
            final FileAppendMeta appendMeta,
            final BaseTransactionMeta baseMeta,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);

        final var bytesAdded = appendMeta.bytesAdded();
        accumulator.addBpt(LONG_BASIC_ENTITY_ID_SIZE + bytesAdded);
        accumulator.addSbs(bytesAdded * appendMeta.lifetime());
    }

    public FeeData fileCreateUsage(final TransactionBody fileCreation, final SigUsage sigUsage) {
        final var op = fileCreation.getFileCreate();

        long customBytes = 0;
        customBytes += op.getContents().size();
        customBytes += op.getMemoBytes().size();
        customBytes += keySizeIfPresent(op, FileCreateTransactionBody::hasKeys, body -> asKey(body.getKeys()));

        final var lifetime = ESTIMATOR_UTILS.relativeLifetime(
                fileCreation, op.getExpirationTime().getSeconds());

        final var estimate = txnEstimateFactory.get(sigUsage, fileCreation, ESTIMATOR_UTILS);
        /* Variable bytes plus a long for expiration time */
        estimate.addBpt(customBytes + LONG_SIZE);
        estimate.addSbs((bytesInBaseRepr() + customBytes) * lifetime);
        estimate.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

        return estimate.get();
    }

    public FeeData fileInfoUsage(final Query fileInfoReq, final ExtantFileContext ctx) {
        final var op = fileInfoReq.getFileGetInfo();

        final var estimate = queryEstimateFactory.apply(op.getHeader().getResponseType());
        estimate.addTb(BASIC_ENTITY_ID_SIZE);
        long extraSb = 0;
        extraSb += ctx.currentMemo().getBytes(StandardCharsets.UTF_8).length;
        extraSb += getAccountKeyStorageSize(asKey(ctx.currentWacl()));
        estimate.addSb(BASE_FILEINFO_SIZE + extraSb);

        return estimate.get();
    }

    public FeeData fileUpdateUsage(
            final TransactionBody fileUpdate, final SigUsage sigUsage, final ExtantFileContext ctx) {
        final var op = fileUpdate.getFileUpdate();

        final long keyBytesUsed = op.hasKeys() ? getAccountKeyStorageSize(asKey(op.getKeys())) : 0;
        final long msgBytesUsed = BASIC_ENTITY_ID_SIZE
                + op.getContents().size()
                + op.getMemo().getValueBytes().size()
                + keyBytesUsed
                + (op.hasExpirationTime() ? LONG_SIZE : 0);
        final var estimate = txnEstimateFactory.get(sigUsage, fileUpdate, ESTIMATOR_UTILS);
        estimate.addBpt(msgBytesUsed);

        long newCustomBytes = 0;
        newCustomBytes += op.getContents().isEmpty()
                ? ctx.currentSize()
                : op.getContents().size();
        newCustomBytes += !op.hasMemo()
                ? ctx.currentMemo().getBytes(StandardCharsets.UTF_8).length
                : op.getMemo().getValueBytes().size();
        newCustomBytes += !op.hasKeys() ? getAccountKeyStorageSize(asKey(ctx.currentWacl())) : keyBytesUsed;
        final long oldCustomBytes = ctx.currentNonBaseSb();
        final long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(fileUpdate, ctx.currentExpiry());
        final long newLifetime = ESTIMATOR_UTILS.relativeLifetime(
                fileUpdate, op.getExpirationTime().getSeconds());
        final long sbsDelta = ESTIMATOR_UTILS.changeInBsUsage(oldCustomBytes, oldLifetime, newCustomBytes, newLifetime);
        if (sbsDelta > 0) {
            estimate.addSbs(sbsDelta);
        }

        return estimate.get();
    }

    public static Key asKey(final KeyList wacl) {
        return Key.newBuilder().setKeyList(wacl).build();
    }
}
