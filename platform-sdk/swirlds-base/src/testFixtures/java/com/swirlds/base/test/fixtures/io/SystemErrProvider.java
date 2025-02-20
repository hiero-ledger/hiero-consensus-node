// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;

/**
 * Provides access to the lines written to {@link System#err}. This is used to verify the output of {@link System#err}
 * for test. A test that uses this interface must be annotated with {@link WithSystemError}. By doing so a
 * {@link SystemErrProvider} instance can be injected into the test by using the {@link jakarta.inject.Inject}
 * annotation.
 *
 * @see WithSystemError
 */
public interface SystemErrProvider {

    /**
     * Returns a stream of lines written to {@link System#err}.
     *
     * @return a stream of lines written to {@link System#err}
     */
    @NonNull
    Stream<String> getLines();
}
