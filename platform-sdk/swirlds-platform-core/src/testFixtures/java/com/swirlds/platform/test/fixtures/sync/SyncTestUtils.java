// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.internal.EventImpl;
import java.util.Collection;

public class SyncTestUtils {

    public static void printEvents(final String heading, final Collection<? extends EventImpl> events) {
        System.out.println("\n--- " + heading + " ---");
        events.forEach(System.out::println);
    }
}
