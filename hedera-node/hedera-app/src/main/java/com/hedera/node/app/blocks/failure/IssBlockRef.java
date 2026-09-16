// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;

/**
 * A single block staged for ISS triage, with all of its on-disk artifacts.
 *
 * @param blockNumber the block number of this block
 * @param files the block's on-disk files: the contents file ({@code .blk.gz}/{@code .pnd.gz}/{@code .open.gz}) plus,
 * for a pending block, its {@code .pnd.json} proof sidecar
 */
public record IssBlockRef(long blockNumber, @NonNull List<Path> files) {
    public IssBlockRef {
        files = List.copyOf(requireNonNull(files));
    }
}
