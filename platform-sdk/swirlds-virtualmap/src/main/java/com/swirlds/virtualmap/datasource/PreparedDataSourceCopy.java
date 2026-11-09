// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import edu.umd.cs.findbugs.annotations.NonNull;

/// A copy of a [VirtualDataSource], prepared by [VirtualDataSourceBuilder#prepareCopy].
///
/// A copy is made in two steps. [VirtualDataSourceBuilder#prepareCopy] captures the state of
/// the source data source, and must be called while the source isn't modified. [#complete()]
/// creates the copy from the captured state, and may be called after the source is modified again.
/// This way, the source is held unmodified only for as long as it takes to capture its state.
///
/// [#close()] must always be called, whether the copy is completed or not. It releases what
/// the prepared copy holds, unless it has been handed over to the completed copy.
public interface PreparedDataSourceCopy extends AutoCloseable {

    /// Creates the data source copy. May be called at most once.
    ///
    /// @return the data source copy, the caller is responsible for closing it
    @NonNull
    VirtualDataSource complete();

    /// Releases the resources of this prepared copy, which are not used by the completed copy. If the
    /// copy hasn't been completed, all resources are released. Never throws.
    @Override
    void close();
}
