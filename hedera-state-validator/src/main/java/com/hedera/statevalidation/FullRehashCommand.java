// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.MerkleHasher;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.rehash.FullRehasher;
import com.swirlds.virtualmap.rehash.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.rehash.VirtualHasherFullRehasher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Performs a full rehash of the state virtual map with a selected hashing implementation and
 * saves all hash chunks to the data source. The computed root hash is compared to the original
 * state hash. Intended to compare full rehash implementations with different parallelism.
 *
 * <p>The rehash timeout is controlled by {@code -DfullRehashTimeoutMs}.
 */
@Command(
        name = "full-rehash",
        description = "Performs a full rehash of the state, saving all hash chunks, and checks the root hash.")
public class FullRehashCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(FullRehashCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(
            names = {"-t", "--type"},
            required = true,
            description = "Hashing type: 'old' (VirtualHasher based, as in VirtualMap full leaf rehash) "
                    + "or 'new' (task per node based).")
    private String type;

    @Option(
            names = {"-p", "--parallelism"},
            description = "Fork-join pool parallelism. Defaults to the number of available processors.")
    private int parallelism = Runtime.getRuntime().availableProcessors();

    private FullRehashCommand() {}

    @Override
    public void run() {
        if (parallelism < 1) {
            throw new ParameterException(spec.commandLine(), "Parallelism must be positive: " + parallelism);
        }
        final String hashingType = type.toLowerCase(Locale.ROOT);
        if (!hashingType.equals("old") && !hashingType.equals("new")) {
            throw new ParameterException(spec.commandLine(), "Unknown hashing type: " + type + ", expected old|new");
        }

        parent.resolveAndGetStateDir();

        /*//TODO remove after local testing
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }*/

        final VirtualMapState state = StateUtils.getDefaultState();
        final Hash originalHash = StateUtils.getOriginalStateHash();
        final VirtualMap virtualMap = state.getRoot();
        final VirtualDataSource dataSource = virtualMap.getDataSource();

        final ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            final FullRehasher rehasher =
                    hashingType.equals("old") ? new VirtualHasherFullRehasher(pool) : new TaskPerNodeFullRehasher(pool);
            log.info(
                    "Starting full rehash: type={}, parallelism={}, timeout={} ms, leaf path range: {} - {}",
                    hashingType,
                    parallelism,
                    virtualMap.getVirtualMapConfig().fullRehashTimeoutMs(),
                    dataSource.getFirstLeafPath(),
                    dataSource.getLastLeafPath());

            final long start = System.currentTimeMillis();
            final Hash computedHash = rehasher.rehash(virtualMap);
            final long duration = System.currentTimeMillis() - start;

            log.info("Full rehash (type={}, parallelism={}) took {} ms", hashingType, parallelism, duration);
            System.out.printf("Full rehash (type=%s, parallelism=%d) took %d ms%n", hashingType, parallelism, duration);

            if (!Objects.equals(originalHash, computedHash)) {
                throw new IllegalStateException(
                        String.format("Root hash mismatch. Expected <%s> but was <%s>", originalHash, computedHash));
            }
            final Hash storedHash = storedRootHash(dataSource);
            if (!Objects.equals(computedHash, storedHash)) {
                throw new IllegalStateException(String.format(
                        "Root hash calculated from saved hash chunks mismatch. Expected <%s> but was <%s>",
                        computedHash, storedHash));
            }
            log.info("Root hash matches the original state hash and saved hash chunks: {}", computedHash);
            System.out.printf("Root hash matches the original state hash and saved hash chunks: %s%n", computedHash);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Calculates the root hash from the top hash chunk saved to the data source.
     */
    private static Hash storedRootHash(final VirtualDataSource dataSource) {
        if (dataSource.getFirstLeafPath() < 1) {
            return null;
        }
        try {
            final VirtualHashChunk rootChunk = dataSource.loadHashChunk(0);
            if (rootChunk == null) {
                return null;
            }
            return rootChunk.chunkRootHash(
                    MerkleHasher.threadSafeDefault(), dataSource.getFirstLeafPath(), dataSource.getLastLeafPath());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
