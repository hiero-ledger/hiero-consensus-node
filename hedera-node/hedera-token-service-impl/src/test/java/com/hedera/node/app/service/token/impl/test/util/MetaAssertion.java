// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.spi.workflows.PreHandleContext;

/**
 * Basic assertions for the pre-handle context.
 */
public class MetaAssertion {
    /**
     * Basic pre-handle context assertions.
     * @param context the context
     * @param keysSize the size of the keys
     */
    public static void basicContextAssertions(final PreHandleContext context, final int keysSize) {
        assertEquals(keysSize, context.requiredNonPayerKeys().size());
    }
}
