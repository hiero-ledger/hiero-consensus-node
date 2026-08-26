// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Future;
import org.hiero.base.crypto.Hash;

/**
 * A no-op implementation of {@link StateLifecycleManager} for use in utilities and tests.
 *
 * @param <S> the type of the state
 * @param <D> the type of the root node of a Merkle tree
 */
public class NoOpStateLifecycleManager<S, D> implements StateLifecycleManager<S, D> {

    @Override
    public S createStateFrom(@NonNull final D rootNode) {
        return null;
    }

    @Override
    public S getMutableState() {
        return null;
    }

    @Override
    public S getLatestImmutableState() {
        return null;
    }

    @Override
    public void createSnapshot(@NonNull final S state, @NonNull final Path targetPath) {}

    @Override
    public Future<Void> createSnapshotAsync(@NonNull final S state, @NonNull final Path targetPath) {
        return null;
    }

    @Override
    public @NonNull Hash loadSnapshot(@NonNull final Path targetPath) throws IOException {
        return new Hash();
    }

    @Override
    public void initWithState(@NonNull final S state) {}

    @Override
    public S copyMutableState() {
        return null;
    }
}
