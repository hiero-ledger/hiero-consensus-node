/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec.utilops.grouping;

import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.REALM;
import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.SHARD;

import com.hedera.hapi.node.base.FileID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
            final Bytes contents = map.get(new FileID(SHARD, REALM, sysfileNub));
            observer.accept(contents);
        };
        return new SysFileLookups(fileNum -> fileNum == sysfileNub, temp);
    }
}
