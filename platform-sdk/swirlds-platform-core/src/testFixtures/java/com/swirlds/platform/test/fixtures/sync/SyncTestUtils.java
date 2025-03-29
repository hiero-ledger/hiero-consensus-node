// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;

public class SyncTestUtils {

    public static void printEvents(final String heading, final Collection<? extends EventImpl> events) {
        System.out.println("\n--- " + heading + " ---");
        events.forEach(System.out::println);
    }

    public static void printTasks(final String heading, final Collection<PlatformEvent> events) {
        System.out.println("\n--- " + heading + " ---");
        events.forEach(System.out::println);
    }

    public static void printTipSet(final String nodeName, final SyncNode node) {
        System.out.printf("\n--- %s's tipSet ---%n", nodeName);
        node.getShadowGraph().getTips().forEach(tip -> System.out.println(tip.getEvent()));
    }

    public static long getMaxIndicator(final List<ShadowEvent> tips, @NonNull final AncientMode ancientMode) {
        long maxIndicator = ancientMode.getGenesisIndicator();
        for (final ShadowEvent tip : tips) {
            maxIndicator = Math.max(ancientMode.selectIndicator(tip.getEvent()), maxIndicator);
        }
        return maxIndicator;
    }

    public static long getMinIndicator(@NonNull final Set<ShadowEvent> events, @NonNull final AncientMode ancientMode) {
        long minIndicator = Long.MAX_VALUE;
        for (final ShadowEvent event : events) {
            minIndicator = Math.min(ancientMode.selectIndicator(event.getEvent()), minIndicator);
        }
        return minIndicator == Long.MAX_VALUE ? ancientMode.getGenesisIndicator() : minIndicator;
    }
}
