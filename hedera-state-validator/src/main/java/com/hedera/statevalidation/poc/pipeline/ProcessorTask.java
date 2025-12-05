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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProcessorTask implements Callable<Void> {

    private static final Logger log = LogManager.getLogger(ProcessorTask.class);

    private final List<ValidationListener> validationListeners;

    private final CopyOnWriteArraySet<Validator> p2kvValidators;
    private final CopyOnWriteArraySet<Validator> p2hValidators;
    private final CopyOnWriteArraySet<Validator> k2pValidators;

    private final MerkleDbDataSource vds;

    private final BlockingQueue<List<ItemData>> dataQueue;

    private final LongList pathToDiskLocationLeafNodes;
    private final LongList pathToDiskLocationInternalNodes;
    private final LongList bucketIndexToBucketLocation;

    private final DataStats dataStats;

    public ProcessorTask(
            @NonNull final Map<Type, CopyOnWriteArraySet<Validator>> validators,
            @NonNull final List<ValidationListener> validationListeners,
            @NonNull final BlockingQueue<List<ItemData>> dataQueue,
            @NonNull final MerkleDbDataSource vds,
            @NonNull final DataStats dataStats) {
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
    }

    @Override
    public Void call() {
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
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Processor task interrupted.");
        }
        return null;
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
            final long path = virtualLeafBytes.path();

            if (data.location() == pathToDiskLocationLeafNodes.get(path)) {
                // live object, perform ops on it...
                if (p2kvValidators == null || p2kvValidators.isEmpty()) {
                    return;
                }
                p2kvValidators.forEach(validator -> {
                    try {
                        ((LeafBytesValidator) validator).processLeafBytes(data.location(), virtualLeafBytes);
                    } catch (final ValidationException e) {
                        // Remove validator and notify listeners only once (removeIf returns true only for the thread
                        // that removes)
                        if (p2kvValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(e));
                        }
                    } catch (final Exception e) {
                        if (p2kvValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getTag(), "Unexpected exception: " + e.getMessage(), e)));
                        }
                    }
                });
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
        } catch (final Exception e) {
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
                if (p2hValidators == null || p2hValidators.isEmpty()) {
                    return;
                }
                p2hValidators.forEach(validator -> {
                    try {
                        ((HashRecordValidator) validator).processHashRecord(virtualHashRecord);
                    } catch (final ValidationException e) {
                        // Remove validator and notify listeners only once (removeIf returns true only for the thread
                        // that removes)
                        if (p2hValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(e));
                        }
                    } catch (final Exception e) {
                        // Remove validator and notify listeners only once (removeIf returns true only for the thread
                        // that removes)
                        if (p2hValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getTag(), "Unexpected exception: " + e.getMessage(), e)));
                        }
                    }
                });
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
        } catch (final Exception e) {
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
                if (k2pValidators == null || k2pValidators.isEmpty()) {
                    return;
                }
                k2pValidators.forEach(validator -> {
                    try {
                        ((HdhmBucketValidator) validator).processBucket(data.location(), bucket);
                    } catch (final ValidationException e) {
                        // Remove validator and notify listeners only once (removeIf returns true only for the thread
                        // that removes)
                        if (k2pValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(e));
                        }
                    } catch (final Exception e) {
                        // Remove validator and notify listeners only once (removeIf returns true only for the thread
                        // that removes)
                        if (k2pValidators.removeIf(v -> v.getTag().equals(validator.getTag()))) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getTag(), "Unexpected exception: " + e.getMessage(), e)));
                        }
                    }
                });
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
        } catch (final Exception e) {
            dataStats.getK2p().incrementParseErrorCount();
            LogUtils.printFileDataLocationErrorPoc(
                    log, e.getMessage(), vds.getKeyToPath().getFileCollection(), data);
        }
    }
}
