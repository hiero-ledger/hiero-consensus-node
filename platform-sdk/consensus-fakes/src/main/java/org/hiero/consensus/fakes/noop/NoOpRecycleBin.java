// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.fakes.noop;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.io.RecycleBin;

/**
 * A no-op {@link RecycleBin} implementation.
 */
public class NoOpRecycleBin implements RecycleBin {

    @Override
    public void recycle(@NonNull Path path) throws IOException {}

    @Override
    public void start() {}

    @Override
    public void stop() {}
}
