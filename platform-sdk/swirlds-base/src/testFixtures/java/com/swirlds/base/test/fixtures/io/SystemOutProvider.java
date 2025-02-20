// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;

/**
 * Provides access to the lines written to {@link System#out}. This is used to verify the output of {@link System#out}
 * for test. A test that uses this interface must be annotated with {@link WithSystemOut}. By doing so a
 * {@link SystemOutProvider} instance can be injected into the test by using the {@link jakarta.inject.Inject}
 * annotation.
 *
 * @see WithSystemOut
 */
public interface SystemOutProvider {

    /**
     * Returns a stream of lines written to {@link System#out}.
     *
     * @return a stream of lines written to {@link System#out}
     */
    @NonNull
    Stream<String> getLines();
}
