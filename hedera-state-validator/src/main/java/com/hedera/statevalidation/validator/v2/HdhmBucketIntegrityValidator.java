// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.v2;

import static com.hedera.statevalidation.util.ConfigUtils.COLLECTED_INFO_THRESHOLD;
import static com.hedera.statevalidation.util.LogUtils.printFileDataLocationError;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.reflect.BucketIterator;
import com.hedera.statevalidation.validator.v2.util.ValidationAssertions;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see HdhmBucketValidator
 */
public class HdhmBucketIntegrityValidator implements HdhmBucketValidator {

    private static final Logger log = LogManager.getLogger(HdhmBucketIntegrityValidator.class);

    public static final String HDHM_TAG = "hdhm";

    private DataFileCollection keyToPathDfc;
    private DataFileCollection pathToKeyValueDfc;
    private LongList pathToDiskLocationLeafNodes;

    private final AtomicLong processedCount = new AtomicLong(0);

    private final CopyOnWriteArrayList<StalePathInfo> stalePathsInfos = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NullLeafInfo> nullLeafsInfo = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UnexpectedKeyInfo> unexpectedKeyInfos = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PathMismatchInfo> pathMismatchInfos = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HashCodeMismatchInfo> hashCodeMismatchInfos = new CopyOnWriteArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getTag() {
        return HDHM_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final DeserializedSignedState deserializedSignedState) {
        //noinspection resource
        final MerkleNodeState state =
                deserializedSignedState.reservedSignedState().get().getState();
        final VirtualMap virtualMap = (VirtualMap) state.getRoot();
        Objects.requireNonNull(virtualMap);
        final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        this.pathToKeyValueDfc = vds.getPathToKeyValue().getFileCollection();
        this.keyToPathDfc = vds.getKeyToPath().getFileCollection();

        this.pathToDiskLocationLeafNodes = vds.getPathToDiskLocationLeafNodes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processBucket(long bucketLocation, @NonNull final ParsedBucket bucket) {
        Objects.requireNonNull(pathToKeyValueDfc);
        Objects.requireNonNull(keyToPathDfc);
        Objects.requireNonNull(pathToDiskLocationLeafNodes);

        final int bucketIndex = bucket.getBucketIndex();

        try {
            final BucketIterator bucketIterator = new BucketIterator(bucket);
            while (bucketIterator.hasNext()) {
                final ParsedBucket.BucketEntry entry = bucketIterator.next();
                final Bytes keyBytes = entry.getKeyBytes();
                final long path = entry.getValue();
                // get path -> dataLocation
                final long dataLocation = pathToDiskLocationLeafNodes.get(path);
                if (dataLocation == 0) {
                    printFileDataLocationError(log, "Stale path", keyToPathDfc, bucketLocation);
                    collectInfo(new StalePathInfo(path, parseKey(keyBytes)), stalePathsInfos);
                    continue;
                }
                final BufferedData leafData = pathToKeyValueDfc.readDataItem(dataLocation);
                if (leafData == null) {
                    printFileDataLocationError(log, "Null leaf", keyToPathDfc, bucketLocation);
                    collectInfo(new NullLeafInfo(path, parseKey(keyBytes)), nullLeafsInfo);
                    continue;
                }
                final VirtualLeafBytes<?> leafBytes = VirtualLeafBytes.parseFrom(leafData);
                if (!keyBytes.equals(leafBytes.keyBytes())) {
                    printFileDataLocationError(log, "Leaf key mismatch", keyToPathDfc, bucketLocation);
                    collectInfo(
                            new UnexpectedKeyInfo(path, parseKey(keyBytes), parseKey(leafBytes.keyBytes())),
                            unexpectedKeyInfos);
                }
                if (leafBytes.path() != path) {
                    printFileDataLocationError(log, "Leaf path mismatch", keyToPathDfc, bucketLocation);
                    collectInfo(new PathMismatchInfo(path, leafBytes.path(), parseKey(keyBytes)), pathMismatchInfos);
                    continue;
                }
                final int hashCode = entry.getHashCode();
                if ((hashCode & bucketIndex) != bucketIndex) {
                    printFileDataLocationError(log, "Hash code mismatch", keyToPathDfc, bucketLocation);
                    collectInfo(new HashCodeMismatchInfo(hashCode, bucketIndex), hashCodeMismatchInfos);
                }
            }
        } catch (Exception e) {
            if (bucketLocation != 0) {
                printFileDataLocationError(log, e.getMessage(), keyToPathDfc, bucketLocation);
            }
        } finally {
            processedCount.incrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.debug("Checked {} Bucket entries", processedCount.get());

        if (!stalePathsInfos.isEmpty()) {
            log.error("Stale path info:\n{}", stalePathsInfos);
            log.error(
                    "There are {} records with stale paths, please check the logs for more info",
                    stalePathsInfos.size());
        }

        if (!nullLeafsInfo.isEmpty()) {
            log.error("Null leaf info:\n{}", stalePathsInfos);
            log.error(
                    "There are {} records with null leafs, please check the logs for more info",
                    stalePathsInfos.size());
        }

        if (!unexpectedKeyInfos.isEmpty()) {
            log.error("Unexpected key info:\n{}", unexpectedKeyInfos);
            log.error(
                    "There are {} records with unexpected keys, please check the logs for more info",
                    unexpectedKeyInfos.size());
        }

        if (!pathMismatchInfos.isEmpty()) {
            log.error("Path mismatch info:\n{}", pathMismatchInfos);
            log.error(
                    "There are {} records with mismatched paths, please check the logs for more info",
                    pathMismatchInfos.size());
        }

        if (!hashCodeMismatchInfos.isEmpty()) {
            log.error("Hash code mismatch info:\n{}", hashCodeMismatchInfos);
            log.error(
                    "There are {} records with mismatch hash codes, please, check the logs for more info",
                    hashCodeMismatchInfos.size());
        }

        ValidationAssertions.requireTrue(
                stalePathsInfos.isEmpty()
                        && nullLeafsInfo.isEmpty()
                        && unexpectedKeyInfos.isEmpty()
                        && pathMismatchInfos.isEmpty()
                        && hashCodeMismatchInfos.isEmpty(),
                getTag(),
                "One of the test condition hasn't been met. "
                        + "Conditions: "
                        + ("stalePathsInfos.isEmpty() = %s, "
                                        + "nullLeafsInfo.isEmpty() = %s, "
                                        + "unexpectedKeyInfos.isEmpty() = %s, "
                                        + "pathMismatchInfos.isEmpty() = %s, "
                                        + "hashCodeMismatchInfos.isEmpty() = %s")
                                .formatted(
                                        stalePathsInfos.isEmpty(),
                                        nullLeafsInfo.isEmpty(),
                                        unexpectedKeyInfos.isEmpty(),
                                        pathMismatchInfos.isEmpty(),
                                        hashCodeMismatchInfos.isEmpty()));
    }

    // ---

    private static StateKey parseKey(Bytes keyBytes) throws ParseException {
        return StateKey.PROTOBUF.parse(keyBytes);
    }

    private static <T> void collectInfo(T info, CopyOnWriteArrayList<T> list) {
        if (COLLECTED_INFO_THRESHOLD == 0 || list.size() < COLLECTED_INFO_THRESHOLD) {
            list.add(info);
        }
    }

    // Bucket entry path is not found in the leaf index
    record StalePathInfo(long path, StateKey key) {
        @Override
        @NonNull
        public String toString() {
            return "StalePathInfo{" + "path=" + path + ", key=" + key + "}\n";
        }
    }

    // Bucket entry path is in the leaf index, but leaf data cannot be loaded
    private record NullLeafInfo(long path, StateKey key) {
        @Override
        @NonNull
        public String toString() {
            return "NullLeafInfo{" + "path=" + path + ", key=" + key + "}\n";
        }
    }

    // Bucket entry key doesn't match leaf key, leaf is loaded by entry path
    record UnexpectedKeyInfo(long path, StateKey expectedKey, StateKey actualKey) {
        @Override
        @NonNull
        public String toString() {
            return "UnexpectedKeyInfo{" + "path="
                    + path + ", expectedKey="
                    + expectedKey + ", actualKey="
                    + actualKey + "}\n";
        }
    }

    // Bucket entry path doesn't match leaf path, leaf is loaded by entry path
    private record PathMismatchInfo(long expectedPath, long actualPath, StateKey key) {
        @Override
        @NonNull
        public String toString() {
            return "PathMismatchInfo{" + "expectedPath="
                    + expectedPath + ", actualPath="
                    + actualPath + ", key="
                    + key + "}\n";
        }
    }

    // Bucket entry hash code doesn't match bucket index (modulo HDHM resize)
    private record HashCodeMismatchInfo(int entryHashCode, int bucketIndex) {
        @Override
        @NonNull
        public String toString() {
            return "HashCodeMismatchInfo{" + "entryHashCode=" + entryHashCode + ", bucketIndex=" + bucketIndex + "}\n";
        }
    }
}
