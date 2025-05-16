// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.result.SingleNodeFilesResult;

/**
 * Assertion class for {@link SingleNodeFilesResult}.
 *
 * <p>Provides custom assertions for validating log results of a single node.
 */
public class SingleNodeFilesResultAssert extends AbstractAssert<SingleNodeFilesResultAssert, SingleNodeFilesResult> {

    protected SingleNodeFilesResultAssert(@Nullable final SingleNodeFilesResult actual) {
        super(actual, SingleNodeFilesResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeFilesResult}.
     *
     * @param actual the actual {@link SingleNodeFilesResult} to assert
     * @return a new instance of {@link SingleNodeFilesResultAssert}
     */
    public static SingleNodeFilesResultAssert assertThat(@Nullable SingleNodeFilesResult actual) {
        return new SingleNodeFilesResultAssert(actual);
    }
}
