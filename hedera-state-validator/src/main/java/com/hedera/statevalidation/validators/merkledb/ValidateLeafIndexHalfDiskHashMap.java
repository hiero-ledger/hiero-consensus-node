// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.validators.Constants.COLLECTED_INFO_THRESHOLD;
import static com.hedera.statevalidation.validators.Constants.VALIDATE_INCORRECT_BUCKET_INDEX_EXCLUSIONS;
import static com.hedera.statevalidation.validators.Constants.VALIDATE_STALE_KEYS_EXCLUSIONS;
import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processRange;
import static com.hedera.statevalidation.validators.Utils.printFileDataLocationError;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireNonNull;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireTrue;

import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.merkledb.reflect.BucketIterator;
import com.hedera.statevalidation.merkledb.reflect.HalfDiskHashMapW;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.validators.StateValidator;
import com.hedera.statevalidation.validators.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This validation should be independent as it goes over the files from the hdhm perspective.
 */
@SuppressWarnings("NewClassNamingConvention")
public class ValidateLeafIndexHalfDiskHashMap implements StateValidator {

    private static final Logger log = LogManager.getLogger(ValidateLeafIndexHalfDiskHashMap.class);

    private boolean skipStaleKeysValidation;

    private boolean skipIncorrectBucketIndexValidation;

    private HalfDiskHashMapW hdhm;

    private LongList pathToDiskLocationLeafNodes;

    private DataFileCollection hdhmDfc;

    private DataFileCollection leafStoreDfc;

    private final CopyOnWriteArrayList<StalePathInfo> stalePathsInfos = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<NullLeafInfo> nullLeafsInfo = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<UnexpectedKeyInfo> unexpectedKeyInfos = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<PathMismatchInfo> pathMismatchInfos = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<HashCodeMismatchInfo> hashCodeMismatchInfos = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Integer> incorrectBucketIndexList = new CopyOnWriteArrayList<>();

    private LongList bucketIndexToBucketLocation;

    public static final String HDHM = "hdhm";

    @Override
    public String getTag() {
        return HDHM;
    }

    @Override
    public void initialize(MerkleNodeState merkleNodeState) {
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        requireNonNull(virtualMap, HDHM);

        skipStaleKeysValidation = VALIDATE_STALE_KEYS_EXCLUSIONS.contains(virtualMap.getLabel());
        skipIncorrectBucketIndexValidation = VALIDATE_INCORRECT_BUCKET_INDEX_EXCLUSIONS.contains(virtualMap.getLabel());

        final MerkleDbDataSource virtualDataSource = (MerkleDbDataSource) virtualMap.getDataSource();

        hdhm = new HalfDiskHashMapW(virtualDataSource.getKeyToPath());
        final var leafStore = new MemoryIndexDiskKeyValueStoreW<>(virtualDataSource.getPathToKeyValue());
        pathToDiskLocationLeafNodes = virtualDataSource.getPathToDiskLocationLeafNodes();
        hdhmDfc = hdhm.getFileCollection();
        leafStoreDfc = leafStore.getFileCollection();
        bucketIndexToBucketLocation = hdhm.getBucketIndexToBucketLocation();
    }

    @Override
    public void processState(MerkleNodeState merkleNodeState) {
        LongConsumer consumer = i -> {
            long bucketLocation = 0;
            try {
                bucketLocation = bucketIndexToBucketLocation.get(i);
                if (bucketLocation == 0) {
                    return;
                }
                final BufferedData bucketData = hdhmDfc.readDataItem(bucketLocation);
                if (bucketData == null) {
                    // FUTURE WORK: report
                    return;
                }
                final ParsedBucket bucket = new ParsedBucket();
                bucket.readFrom(bucketData);
                final int bucketIndex = bucket.getBucketIndex();
                if ((bucketIndex & i) != bucketIndex) {
                    incorrectBucketIndexList.add((int) i);
                }
                var bucketIterator = new BucketIterator(bucket);
                while (bucketIterator.hasNext()) {
                    final ParsedBucket.BucketEntry entry = bucketIterator.next();
                    final Bytes keyBytes = entry.getKeyBytes();
                    final long path = entry.getValue();
                    // get path -> dataLocation
                    var dataLocation = pathToDiskLocationLeafNodes.get(path);
                    if (dataLocation == 0) {
                        printFileDataLocationError(log, "Stale path", hdhmDfc, bucketLocation);
                        collectInfo(new StalePathInfo(path, parseKey(keyBytes)), stalePathsInfos);
                        continue;
                    }
                    final BufferedData leafData = leafStoreDfc.readDataItem(dataLocation);
                    if (leafData == null) {
                        printFileDataLocationError(log, "Null leaf", hdhmDfc, bucketLocation);
                        collectInfo(new NullLeafInfo(path, parseKey(keyBytes)), nullLeafsInfo);
                        continue;
                    }
                    final VirtualLeafBytes<?> leafBytes = VirtualLeafBytes.parseFrom(leafData);
                    if (!keyBytes.equals(leafBytes.keyBytes())) {
                        printFileDataLocationError(log, "Leaf key mismatch", hdhmDfc, bucketLocation);
                        collectInfo(
                                new UnexpectedKeyInfo(path, parseKey(keyBytes), parseKey(leafBytes.keyBytes())),
                                unexpectedKeyInfos);
                    }
                    if (leafBytes.path() != path) {
                        printFileDataLocationError(log, "Leaf path mismatch", hdhmDfc, bucketLocation);
                        collectInfo(
                                new PathMismatchInfo(path, leafBytes.path(), parseKey(keyBytes)), pathMismatchInfos);
                        continue;
                    }
                    final int hashCode = entry.getHashCode();
                    if ((hashCode & bucketIndex) != bucketIndex) {
                        printFileDataLocationError(log, "Hash code mismatch", hdhmDfc, bucketLocation);
                        collectInfo(new HashCodeMismatchInfo(hashCode, bucketIndex), hashCodeMismatchInfos);
                        continue;
                    }
                }
            } catch (Exception e) {
                if (bucketLocation != 0) {
                    printFileDataLocationError(log, e.getMessage(), hdhmDfc, bucketLocation);
                }
                throw new ValidationException(HDHM, "Error in bucket entry processing", e);
            }
        };

        // iterate over all the buckets
        processRange(0, hdhm.getBucketIndexToBucketLocation().size(), consumer).join();
    }

    @Override
    public void validate() {
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

        requireTrue(
                (stalePathsInfos.isEmpty() || skipStaleKeysValidation)
                                && nullLeafsInfo.isEmpty()
                                && unexpectedKeyInfos.isEmpty()
                                && pathMismatchInfos.isEmpty()
                                && incorrectBucketIndexList.isEmpty()
                                && hashCodeMismatchInfos.isEmpty()
                        || skipIncorrectBucketIndexValidation,
                HDHM,
                "One of the test condition hasn't been met. "
                        + "Conditions: "
                        + ("stalePathsInfos.isEmpty() = %s, "
                                        + "nullLeafsInfo.isEmpty() = %s, "
                                        + "unexpectedKeyInfos.isEmpty() = %s, "
                                        + "pathMismatchInfos.isEmpty() = %s, "
                                        + "hashCodeMismatchInfos.isEmpty() = %s, "
                                        + "incorrectBucketIndexList.isEmpty() = %s. "
                                        + "IncorrectBucketIndexInfos: %s")
                                .formatted(
                                        stalePathsInfos.isEmpty(),
                                        nullLeafsInfo.isEmpty(),
                                        unexpectedKeyInfos.isEmpty(),
                                        pathMismatchInfos.isEmpty(),
                                        hashCodeMismatchInfos.isEmpty(),
                                        incorrectBucketIndexList.isEmpty(),
                                        incorrectBucketIndexList));
    }

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
