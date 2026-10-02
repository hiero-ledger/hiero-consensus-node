// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.rehash;

import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.crypto.Hash;

/// Rehashes all leaves of a virtual map directly from its data source and saves all hash chunks
/// back to the data source.
///
/// The map must be flushed, i.e. it must not have any dirty leaves or hashes in its cache, as
/// only the data source is used to read leaves. The rehash must complete within
/// [com.swirlds.virtualmap.config.VirtualMapConfig#fullRehashTimeoutMs()], otherwise a
/// [RuntimeException] caused by a [java.util.concurrent.TimeoutException] is thrown.
public interface FullRehasher {

    /// Rehashes the whole virtual map.
    ///
    /// @param map the virtual map to rehash
    /// @return the root hash, or `null` if the map is empty
    @Nullable
    Hash rehash(@NonNull VirtualMap map);
}
