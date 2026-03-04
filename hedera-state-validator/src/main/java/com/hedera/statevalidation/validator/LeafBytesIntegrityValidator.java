// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;

import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class LeafBytesIntegrityValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(LeafBytesIntegrityValidator.class);

    public static final String LEAF_GROUP = "leaf";

    private VirtualMap virtualMap;
    private HalfDiskHashMap keyToPath;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong exceptionCount = new AtomicLong(0);
    private final AtomicLong pathErrorCount = new AtomicLong(0);
    private final AtomicLong valueErrorCount = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return LEAF_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as currently it is the only one
        return LEAF_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.virtualMap = state.getRoot();
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
        this.keyToPath = vds.getKeyToPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        Objects.requireNonNull(virtualMap);
        Objects.requireNonNull(keyToPath);

        try {
            final Bytes keyBytes = leafBytes.keyBytes();
            final Bytes valueBytes = leafBytes.valueBytes();
            final long p2KvPath = leafBytes.path();
            final long k2pPath = keyToPath.get(keyBytes, -1);

            if (p2KvPath != k2pPath) {
                pathErrorCount.incrementAndGet();
                log.error("Path mismatch. p2KvPath={} vs k2pPath={}", p2KvPath, k2pPath);
            }

            if (!valueBytes.equals(virtualMap.getBytes(keyBytes))) {
                valueErrorCount.incrementAndGet();
                log.error("Value mismatch for path={}, value={}", p2KvPath, parseValue(valueBytes));
            }

            successCount.incrementAndGet();
        } catch (IOException e) {
            exceptionCount.incrementAndGet();
            printFileDataLocationError(log, e.getMessage(), dataLocation);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } finally {
            processedCount.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.debug("Checked {} VirtualLeafBytes entries", processedCount.get());

        final boolean ok = pathErrorCount.get() == 0 && valueErrorCount.get() == 0 && exceptionCount.get() == 0;
        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("%s validation failed. " + "pathErrorCount=%d, valueErrorCount=%d, exceptionCount=%d, successCount=%d")
                        .formatted(
                                getName(),
                                pathErrorCount.get(),
                                valueErrorCount.get(),
                                exceptionCount.get(),
                                successCount.get()));
    }

    private static StateValue parseValue(Bytes valueBytes) throws ParseException {
        return StateValue.PROTOBUF.parse(valueBytes);
    }
}
