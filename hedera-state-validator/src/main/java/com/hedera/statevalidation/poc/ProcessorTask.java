// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc;

import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

public class ProcessorTask implements Runnable {

    private final BlockingQueue<ItemData> dataQueue;

    private final LongList pathToDiskLocationLeafNodes;
    private final LongList pathToDiskLocationInternalNodes;
    private final LongList bucketIndexToBucketLocation;

    private final DataStats dataStats;

    private final CountDownLatch processorsLatch;

    public ProcessorTask(
            BlockingQueue<ItemData> dataQueue,
            MerkleDbDataSource vds,
            DataStats dataStats,
            CountDownLatch processorsLatch) {
        this.dataQueue = dataQueue;

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
                ItemData chunk = dataQueue.take();

                if (chunk.isPoisonPill()) {
                    break;
                }

                processChunk(chunk);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            processorsLatch.countDown();
        }
    }

    private void processChunk(ItemData data) {
        switch (data.type()) {
            case P2KV -> processVirtualLeafBytes(data);
            case P2H -> processVirtualHashRecord(data);
            case K2P -> processBucket(data);
        }
    }

    private void processVirtualLeafBytes(ItemData data) {
        try {
            dataStats.addTotalSpaceBytes(data.bytes().length());
            dataStats.incrementTotalItemCount();

            VirtualLeafBytes virtualLeafBytes =
                    VirtualLeafBytes.parseFrom(data.bytes().toReadableSequentialData());
            long path = virtualLeafBytes.path();

            if (data.location() == pathToDiskLocationLeafNodes.get(path)) {
                // live object, do something...
            } else {
                // add to wasted items/space
                dataStats.addObsoleteSpaceBytes(data.bytes().length());
                dataStats.incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.incrementP2kvFailedToProcessCount();
        }
    }

    private void processVirtualHashRecord(ItemData data) {
        try {
            dataStats.addTotalSpaceBytes(data.bytes().length());
            dataStats.incrementTotalItemCount();

            VirtualHashRecord virtualHashRecord =
                    VirtualHashRecord.parseFrom(data.bytes().toReadableSequentialData());
            final long path = virtualHashRecord.path();

            if (data.location() == pathToDiskLocationInternalNodes.get(path)) {
                // live object, do something...

            } else {
                // add to wasted items/space
                dataStats.addObsoleteSpaceBytes(data.bytes().length());
                dataStats.incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.incrementP2hFailedToProcessCount();
        }
    }

    private void processBucket(ItemData data) {
        try {
            dataStats.addTotalSpaceBytes(data.bytes().length());
            dataStats.incrementTotalItemCount();

            final ParsedBucket bucket = new ParsedBucket();
            bucket.readFrom(data.bytes().toReadableSequentialData());

            if (data.location() == bucketIndexToBucketLocation.get(bucket.getBucketIndex())) {
                // live object, do something...
            } else {
                // add to wasted items/space
                dataStats.addObsoleteSpaceBytes(data.bytes().length());
                dataStats.incrementObsoleteItemCount();
            }
        } catch (Exception e) {
            dataStats.incrementK2pFailedToProcessCount();
        }
    }
}
