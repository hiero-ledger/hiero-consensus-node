// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.grouping;

import com.hedera.hapi.node.base.FileID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiPropertySource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Returns DSL actions that group collections of other operations.
 */
public class GroupingVerbs {
    /**
     * Private constructor to prevent instantiation.
     *
     * @throws UnsupportedOperationException if invoked by reflection or other means.
     */
    private GroupingVerbs() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a utility operation to retrieve the contents of system files and pass them to an observer.
     *
     * @param observer the consumer of the system file contents
     * @return the utility operation
     */
    public static SysFileLookups getSystemFiles(@NonNull final Consumer<Map<FileID, Bytes>> observer) {
        return new SysFileLookups(fileNum -> true, observer);
    }

    /**
     * Returns a utility operation to retrieve the contents of specific system files and pass them to an observer.
     *
     * @param sysfileNub the system file number
     * @param observer the consumer of the system file contents
     * @return the utility operation
     */
    public static SysFileLookups getSystemFiles(final long sysfileNub, @NonNull final Consumer<Bytes> observer) {
        final Consumer<Map<FileID, Bytes>> temp = map -> {
            final Bytes contents = map.get(
                    new FileID(HapiPropertySource.getConfigShard(), HapiPropertySource.getConfigRealm(), sysfileNub));
            observer.accept(contents);
        };
        return new SysFileLookups(fileNum -> fileNum == sysfileNub, temp);
    }
}
