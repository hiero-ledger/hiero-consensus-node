// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.context;

import com.swirlds.base.context.internal.GlobalContext;
import com.swirlds.base.context.internal.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContextTest {

    @Test
    void testOnlyOneGlobalContext() {
        // given
        Context context = Context.getGlobalContext();

        // when
        Context globalContext = GlobalContext.getInstance();

        // then
        Assertions.assertSame(context, globalContext);
    }

    @Test
    void testOnlyOneThreadLocalContext() {
        // given
        Context context = Context.getThreadLocalContext();

        // when
        Context threadLocalContext = ThreadLocalContext.getInstance();

        // then
        Assertions.assertSame(context, threadLocalContext);
    }
}
