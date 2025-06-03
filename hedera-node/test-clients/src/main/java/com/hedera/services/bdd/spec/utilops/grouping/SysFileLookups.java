// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.grouping;

import static com.hedera.node.app.hapi.utils.ByteStringUtils.unwrapUnsafelyIfPossible;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * A utility operation to retrieve the contents of system files whose numbers satisfy a given predicate.
 */
public class SysFileLookups extends UtilOp {
    private final LongPredicate test;
    private final Consumer<Map<FileID, Bytes>> observer;

    public SysFileLookups(@NonNull final LongPredicate test, @NonNull final Consumer<Map<FileID, Bytes>> observer) {
        this.test = requireNonNull(test);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        observer.accept(getSystemFileContents(spec, test));
        return false;
    }

    /**
     * Returns a map of system file contents whose numbers pass the given test for the given spec.
     *
     * @param spec the spec
     * @param test the predicate to filter the system file numbers
     * @return the map of system file contents
     */
    public static Map<FileID, Bytes> getSystemFileContents(
            @NonNull final HapiSpec spec, @NonNull final LongPredicate test) {
        return allSystemFileNums(spec)
                .filter(test)
                .boxed()
                .collect(Collectors.toMap(fileNum -> new FileID(spec.shard(), spec.realm(), fileNum), fileNum -> {
                    final var query = getFileContents(String.valueOf(fileNum)).noLogging();
                    allRunFor(spec, query);
                    final var contents = query.getResponse()
                            .getFileGetContents()
                            .getFileContents()
                            .getContents();
                    return Bytes.wrap(unwrapUnsafelyIfPossible(contents));
                }));
    }

    /**
     * Returns a stream of all system file numbers for he given spec.
     *
     * @param spec the spec
     * @return the stream of system file numbers
     */
    public static LongStream allSystemFileNums(@NonNull final HapiSpec spec) {
        final var startupProperties = spec.startupProperties();
        final var updateFilesRange = startupProperties.getLongPair("files.softwareUpdateRange");
        return LongStream.concat(
                LongStream.of(
                        startupProperties.getLong("files.addressBook"),
                        startupProperties.getLong("files.nodeDetails"),
                        startupProperties.getLong("files.feeSchedules"),
                        startupProperties.getLong("files.exchangeRates"),
                        startupProperties.getLong("files.networkProperties"),
                        startupProperties.getLong("files.hapiPermissions"),
                        startupProperties.getLong("files.throttleDefinitions")),
                LongStream.rangeClosed(updateFilesRange.left(), updateFilesRange.right()));
    }
}
