// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.poc.DataStats;
import com.hedera.statevalidation.poc.ItemData;
import com.hedera.statevalidation.poc.ItemData.Type;
import com.hedera.statevalidation.poc.ProcessorTask;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileIterator;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "poc")
public class PocCommand implements Runnable {

    @ParentCommand
    private StateOperatorCommand parent;

    @Option(
            names = {"-io", "--io-threads"},
            description = "Number of IO threads for reading from disk.")
    private int ioThreads = 2;

    @Option(
            names = {"-p", "--process-threads"},
            description = "Number of CPU threads for processing chunks.")
    private int processThreads = 2;

    @Option(
            names = {"-b", "--queue-capacity"},
            description = "Queue capacity for backpressure control.")
    private int queueCapacity = 1000;

    private PocCommand() {}

    @Override
    public void run() {
        try {
            BlockingQueue<ItemData> dataQueue = new LinkedBlockingQueue<>(queueCapacity);

            ExecutorService ioPool = Executors.newFixedThreadPool(ioThreads);
            ExecutorService processPool = Executors.newFixedThreadPool(processThreads);

            long startTime = System.currentTimeMillis();

            // Initialize state and get data file collections
            parent.initializeStateDir();
            DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            MerkleNodeState state =
                    deserializedSignedState.reservedSignedState().get().getState();
            VirtualMap virtualMap = (VirtualMap) state.getRoot();
            MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

            DataFileCollection pathToKeyValueDfc = vds.getPathToKeyValue().getFileCollection();
            DataFileCollection pathToHashDfc = vds.getHashStoreDisk().getFileCollection();
            DataFileCollection keyToPathDfc = vds.getKeyToPath().getFileCollection();

            // Count total readers upfront
            int totalReaders = pathToKeyValueDfc.getAllCompletedFiles().size()
                    + pathToHashDfc.getAllCompletedFiles().size()
                    + keyToPathDfc.getAllCompletedFiles().size();

            CountDownLatch readerLatch = new CountDownLatch(totalReaders);
            CountDownLatch processorsLatch = new CountDownLatch(processThreads);

            DataStats dataStats = new DataStats();

            // Start processor threads
            for (int i = 0; i < processThreads; i++) {
                processPool.submit(new ProcessorTask(dataQueue, vds, dataStats, processorsLatch));
            }

            // Submit reader tasks
            submitReaderTasksFor(pathToKeyValueDfc, dataQueue, Type.P2KV, ioPool, readerLatch);
            submitReaderTasksFor(pathToHashDfc, dataQueue, Type.P2H, ioPool, readerLatch);
            submitReaderTasksFor(keyToPathDfc, dataQueue, Type.K2P, ioPool, readerLatch);

            // Wait for all readers to finish
            readerLatch.await();
            ioPool.shutdown();
            if (!ioPool.awaitTermination(1, TimeUnit.MINUTES)) {
                throw new RuntimeException("IO pool did not terminate within timeout");
            }

            // Send one poison pill per processor
            for (int i = 0; i < processThreads; i++) {
                dataQueue.put(ItemData.poisonPill());
            }

            // Wait for processors to finish
            processorsLatch.await();
            processPool.shutdown();
            if (!processPool.awaitTermination(1, TimeUnit.MINUTES)) {
                throw new RuntimeException("Process pool did not terminate within timeout");
            }

            System.out.println(dataStats);
            long elapsedTime = System.currentTimeMillis() - startTime;
            System.out.println("Total processing time: " + elapsedTime + " ms");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void submitReaderTasksFor(
            DataFileCollection dfc,
            BlockingQueue<ItemData> dataQueue,
            ItemData.Type dataType,
            ExecutorService ioPool,
            CountDownLatch readerLatch) {
        for (DataFileReader reader : dfc.getAllCompletedFiles()) {
            ioPool.submit(() -> {
                try {
                    try (DataFileIterator dataIterator = reader.createIterator()) {
                        while (dataIterator.next()) {
                            BufferedData originalData = dataIterator.getDataItemData();
                            Bytes dataCopy = originalData.getBytes(0, originalData.remaining());

                            ItemData itemData =
                                    new ItemData(dataType, dataCopy, dataIterator.getDataItemDataLocation());
                            dataQueue.put(itemData);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Reader interrupted", e);
                } catch (Exception e) {
                    throw new RuntimeException("Reader failed", e);
                } finally {
                    readerLatch.countDown();
                }
            });
        }
    }
}
