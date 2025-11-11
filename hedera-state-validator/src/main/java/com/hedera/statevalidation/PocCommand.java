// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.poc.ChunkedFileIterator;
import com.hedera.statevalidation.poc.DataStats;
import com.hedera.statevalidation.poc.ItemData;
import com.hedera.statevalidation.poc.ItemData.Type;
import com.hedera.statevalidation.poc.ProcessorTask;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
            AtomicLong totalBoundarySearchMillis = new AtomicLong(0L);

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

            int totalFiles = pathToKeyValueDfc.getAllCompletedFiles().size()
                    + pathToHashDfc.getAllCompletedFiles().size()
                    + keyToPathDfc.getAllCompletedFiles().size();

            System.out.println("P2KV file count: " + pathToKeyValueDfc.getAllCompletedFiles().size());
            System.out.println("P2H file count: " + pathToHashDfc.getAllCompletedFiles().size());
            System.out.println("K2P file count: " + keyToPathDfc.getAllCompletedFiles().size());
            System.out.println("Total files: " + totalFiles);

            long globalTotalSize = pathToKeyValueDfc.getAllCompletedFiles().stream()
                    .mapToLong(DataFileReader::getSize)
                    .sum()
                    + pathToHashDfc.getAllCompletedFiles().stream()
                    .mapToLong(DataFileReader::getSize)
                    .sum()
                    + keyToPathDfc.getAllCompletedFiles().stream()
                    .mapToLong(DataFileReader::getSize)
                    .sum();

            System.out.println("Global total data size: " + globalTotalSize / (1024 * 1024) + " MB");

            // Plan all tasks (calculate chunks for each file)
            List<FileReadTask> tasks = new ArrayList<>();
            tasks.addAll(planTasksFor(pathToKeyValueDfc, Type.P2KV, ioThreads, globalTotalSize));
            tasks.addAll(planTasksFor(pathToHashDfc, Type.P2H, ioThreads, globalTotalSize));
            tasks.addAll(planTasksFor(keyToPathDfc, Type.K2P, ioThreads, globalTotalSize));

            // Sort tasks: largest chunks first (better thread utilization)
            tasks.sort((a, b) -> Long.compare(
                    b.endByte - b.startByte,
                    a.endByte - a.startByte
            ));

            int totalTasks = tasks.size();

            System.out.println("Total tasks: " + totalTasks);

            CountDownLatch readerLatch = new CountDownLatch(totalTasks);
            CountDownLatch processorsLatch = new CountDownLatch(processThreads);

            DataStats dataStats = new DataStats();

            // Start processor threads
            for (int i = 0; i < processThreads; i++) {
                processPool.submit(new ProcessorTask(dataQueue, vds, dataStats, processorsLatch));
            }

            // Submit with chunking
            // Submit all planned tasks
            for (FileReadTask task : tasks) {
                ioPool.submit(() -> {
                    try {
                        readFileChunk(task.reader, dataQueue, task.type, task.startByte, task.endByte,
                                totalBoundarySearchMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Reader interrupted", e);
                    } catch (Exception e) {
                        throw new RuntimeException("Reader failed for chunk "
                                + task.startByte + "-" + task.endByte, e);
                    } finally {
                        readerLatch.countDown();
                    }
                });
            }

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
            System.out.println("Total boundary search time: " + totalBoundarySearchMillis.get() + " ms");
            System.out.println("Total processing time: " + (System.currentTimeMillis() - startTime) + " ms");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Helper: Plan tasks for one collection
    private List<FileReadTask> planTasksFor(
            DataFileCollection dfc,
            ItemData.Type dataType,
            int ioThreads,
            long globalTotalSize) {

        List<FileReadTask> tasks = new ArrayList<>();

        for (DataFileReader reader : dfc.getAllCompletedFiles()) {
            long fileSize = reader.getSize();
            if (fileSize == 0) {
                continue;
            }

            // Calculate optimal chunks using GLOBAL total
            int chunks = calculateOptimalChunks(reader, ioThreads, globalTotalSize);
            long chunkSize = (fileSize + chunks - 1) / chunks;

            System.out.println(
                    "File: " + reader.getPath().getFileName() + " size: " + fileSize / (1024 * 1024) + " chunks: "
                            + chunks + " chunkSize: " + chunkSize / (1024 * 1024));

            // Create tasks for each chunk
            for (int i = 0; i < chunks; i++) {
                long startByte = i * chunkSize;
                long endByte = Math.min(startByte + chunkSize, fileSize);

                if (startByte >= fileSize) {
                    continue;
                }

                tasks.add(new FileReadTask(reader, dataType, startByte, endByte));
            }
        }

        return tasks;
    }

    private int calculateOptimalChunks(
            DataFileReader reader,
            int ioThreads,
            long globalTotalDataSize) {

        long fileSize = reader.getSize();
        long targetChunkSize = globalTotalDataSize / (ioThreads * 4);

        if (fileSize < targetChunkSize) {
            return 1;
        }

        return (int) Math.ceil((double) fileSize / targetChunkSize);
    }

    private static void readFileChunk(
            DataFileReader reader,
            BlockingQueue<ItemData> dataQueue,
            Type dataType,
            long startByte,
            long endByte, AtomicLong totalBoundarySearchMillis)
            throws IOException, InterruptedException {

        try (ChunkedFileIterator iterator =
                new ChunkedFileIterator(reader.getPath(),
                        reader.getMetadata(), dataType, startByte, endByte, totalBoundarySearchMillis)) {

            while (iterator.next()) {
                BufferedData originalData = iterator.getDataItemData();
                Bytes dataCopy = originalData.getBytes(0, originalData.remaining());

                ItemData itemData = new ItemData(dataType, dataCopy, iterator.getDataItemDataLocation());
                dataQueue.put(itemData);
            }
        }
    }

    // Helper record to hold task information
    private record FileReadTask(
            DataFileReader reader,
            ItemData.Type type,
            long startByte,
            long endByte
    ) {
    }
}
