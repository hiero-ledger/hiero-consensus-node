// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.pipeline;

import com.hedera.statevalidation.poc.listener.ValidationListener;
import com.hedera.statevalidation.poc.model.DataStats;
import com.hedera.statevalidation.poc.model.ItemData;
import com.hedera.statevalidation.poc.model.ItemData.Type;
import com.hedera.statevalidation.poc.util.ValidationException;
import com.hedera.statevalidation.poc.validator.api.HashRecordValidator;
import com.hedera.statevalidation.poc.validator.api.HdhmBucketValidator;
import com.hedera.statevalidation.poc.validator.api.LeafBytesValidator;
import com.hedera.statevalidation.poc.validator.api.Validator;
import com.hedera.statevalidation.util.LogUtils;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProcessorTask implements Runnable {

    private static final Logger log = LogManager.getLogger(ProcessorTask.class);

    private final List<ValidationListener> validationListeners;

    private final Set<Validator> p2kvValidators;
    private final Set<Validator> p2hValidators;
    private final Set<Validator> k2pValidators;

    private final MerkleDbDataSource vds;

    private final BlockingQueue<List<ItemData>> dataQueue;

    private final LongList pathToDiskLocationLeafNodes;
    private final LongList pathToDiskLocationInternalNodes;
    private final LongList bucketIndexToBucketLocation;

    private final DataStats dataStats;

    private final CountDownLatch processorsLatch;

    public ProcessorTask(
            @NonNull final Map<Type, Set<Validator>> validators,
            @NonNull final List<ValidationListener> validationListeners,
            @NonNull final BlockingQueue<List<ItemData>> dataQueue,
            @NonNull final MerkleDbDataSource vds,
            @NonNull final DataStats dataStats,
            @NonNull final CountDownLatch processorsLatch) {
        this.validationListeners = validationListeners;

        this.p2kvValidators = validators.get(Type.P2KV);
        this.p2hValidators = validators.get(Type.P2H);
        this.k2pValidators = validators.get(Type.K2P);

        this.dataQueue = dataQueue;

        this.vds = vds;

        this.pathToDiskLocationLeafNodes = vds.getPathToDiskLocationLeafNodes();
        this.pathToDiskLocationInternalNodes = vds.getPathToDiskLocationInternalNodes();
        this.bucketIndexToBucketLocation = (LongList) vds.getKeyToPath().getBucketIndexToBucketLocation();

        this.dataStats = dataStats;

        this.processorsLatch = processorsLatch;
    }

    @Override
    public void run() {
        try {
            while (true) {
                final List<ItemData> batch = dataQueue.take();
                boolean stop = false;

                for (final ItemData chunk : batch) {
                    if (chunk.isPoisonPill()) {
                        stop = true;
                        break;
                    }

                    processChunk(chunk);
                }

                if (stop) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        } finally {
            processorsLatch.countDown();
        }
    }

    private void processChunk(@NonNull final ItemData data) {
        switch (data.type()) {
            case P2KV -> processVirtualLeafBytes(data);
            case P2H -> processVirtualHashRecord(data);
            case K2P -> processBucket(data);
        }
    }

    private void processVirtualLeafBytes(@NonNull final ItemData data) {
        try {
            dataStats.getP2kv().addSpaceSize(data.bytes().length());
            dataStats.getP2kv().incrementItemCount();

            final VirtualLeafBytes virtualLeafBytes =
                    VirtualLeafBytes.parseFrom(data.bytes().toReadableSequentialData());
            long path = virtualLeafBytes.path();

            if (data.location() == pathToDiskLocationLeafNodes.get(path)) {
                // live object, perform ops on it...
                try {
                    // Explicitly cast here. This is safe, explicit, and has negligible performance cost.
                    p2kvValidators.forEach(validator ->
                            ((LeafBytesValidator) validator).processLeafBytes(data.location(), virtualLeafBytes));
                } catch (ValidationException e) {
                    // remove validator from the set, so it won't be used again
                    p2kvValidators.removeIf(validator -> validator.getTag().equals(e.getValidatorTag()));
                    // notify listeners about the error, so they can log, etc.
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                }
            } else if (data.location() == -1) {
                dataStats.getP2kv().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationErrorPoc(
                        log,
                        "data.location() was -1 for P2KV entry",
                        vds.getPathToKeyValue().getFileCollection(),
                        data);
            } else {
                // add to wasted items/space
                dataStats.getP2kv().addObsoleteSpaceSize(data.bytes().length());
                dataStats.getP2kv().incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.getP2kv().incrementParseErrorCount();
            LogUtils.printFileDataLocationErrorPoc(
                    log, e.getMessage(), vds.getPathToKeyValue().getFileCollection(), data);
        }
    }

    private void processVirtualHashRecord(@NonNull final ItemData data) {
        try {
            dataStats.getP2h().addSpaceSize(data.bytes().length());
            dataStats.getP2h().incrementItemCount();

            final VirtualHashRecord virtualHashRecord =
                    VirtualHashRecord.parseFrom(data.bytes().toReadableSequentialData());
            final long path = virtualHashRecord.path();

            if (data.location() == pathToDiskLocationInternalNodes.get(path)) {
                // live object, perform ops on it...
                try {
                    // Explicitly cast here. This is safe, explicit, and has negligible performance cost.
                    p2hValidators.forEach(
                            validator -> ((HashRecordValidator) validator).processHashRecord(virtualHashRecord));
                } catch (ValidationException e) {
                    // remove validator from the set, so it won't be used again
                    p2hValidators.removeIf(validator -> validator.getTag().equals(e.getValidatorTag()));
                    // notify listeners about the error, so they can log, etc.
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                }
            } else if (data.location() == -1) {
                dataStats.getP2h().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationErrorPoc(
                        log,
                        "data.location() was -1 for P2H entry",
                        vds.getHashStoreDisk().getFileCollection(),
                        data);
            } else {
                // add to wasted items/space
                dataStats.getP2h().addObsoleteSpaceSize(data.bytes().length());
                dataStats.getP2h().incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.getP2h().incrementParseErrorCount();
            LogUtils.printFileDataLocationErrorPoc(
                    log, e.getMessage(), vds.getHashStoreDisk().getFileCollection(), data);
        }
    }

    private void processBucket(@NonNull final ItemData data) {
        try {
            dataStats.getK2p().addSpaceSize(data.bytes().length());
            dataStats.getK2p().incrementItemCount();

            final ParsedBucket bucket = new ParsedBucket();
            bucket.readFrom(data.bytes().toReadableSequentialData());

            if (data.location() == bucketIndexToBucketLocation.get(bucket.getBucketIndex())) {
                // live object, perform ops on it...
                try {
                    // Explicitly cast here. This is safe, explicit, and has negligible performance cost.
                    k2pValidators.forEach(
                            validator -> ((HdhmBucketValidator) validator).processBucket(data.location(), bucket));
                } catch (ValidationException e) {
                    // remove validator from the set, so it won't be used again
                    k2pValidators.removeIf(validator -> validator.getTag().equals(e.getValidatorTag()));
                    // notify listeners about the error, so they can log, etc.
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                }
            } else if (data.location() == -1) {
                dataStats.getK2p().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationErrorPoc(
                        log,
                        "data.location() was -1 for K2P entry",
                        vds.getKeyToPath().getFileCollection(),
                        data);
            } else {
                // add to wasted items/space
                dataStats.getK2p().addObsoleteSpaceSize(data.bytes().length());
                dataStats.getK2p().incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.getK2p().incrementParseErrorCount();
            LogUtils.printFileDataLocationErrorPoc(
                    log, e.getMessage(), vds.getKeyToPath().getFileCollection(), data);
        }
    }
}
