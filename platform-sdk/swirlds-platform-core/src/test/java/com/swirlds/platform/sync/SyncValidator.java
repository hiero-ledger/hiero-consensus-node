// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static com.swirlds.platform.test.fixtures.sync.SyncTestUtils.printEvents;
import static com.swirlds.platform.test.fixtures.sync.SyncTestUtils.printTasks;
import static com.swirlds.platform.test.fixtures.sync.SyncTestUtils.printTipSet;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.fixtures.sync.SyncNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;

public class SyncValidator {

    private static boolean enableLogging;

    public static void assertNoEventsReceived(final SyncNode node) {
        assertNoEventsReceived(node.getNodeId().toString(), node);
    }

    public static void assertNoEventsReceived(final String nodeName, final SyncNode node) {
        if (enableLogging) {
            System.out.println(String.format("*** Asserting that node '%s' received no events ***", nodeName));
        }

        assertTrue(
                node.getReceivedEvents().isEmpty(),
                format(
                        "%s received %d events but should have received none.",
                        nodeName, node.getReceivedEvents().size()));
    }

    public static void assertNoEventsTransferred(final SyncNode caller, final SyncNode listener) {
        if (enableLogging) {
            System.out.println("*** Asserting that neither node received any events ***");
        }
        assertNoEventsReceived("caller", caller);
        assertNoEventsReceived("listener", listener);
    }

    public static void assertRequiredEventsTransferred(
            final SyncNode caller, final SyncNode listener, @NonNull final AncientMode ancientMode) {
        if (enableLogging) {
            printTipSet("Caller's Tip Set", caller);
            printTipSet("Listener's Tip Set", listener);
            System.out.println("*** Asserting that required events were transferred ***");
        }
        compareEventLists(caller, listener, false, ancientMode);
    }

    public static void assertOnlyRequiredEventsTransferred(
            final SyncNode caller, final SyncNode listener, @NonNull final AncientMode ancientMode) {
        if (enableLogging) {
            System.out.println("*** Asserting that only required events were transferred ***");
        }
        compareEventLists(caller, listener, true, ancientMode);
    }

    public static void assertFallenBehindDetection(final boolean fellBehind, final SyncNode... nodes) {
        if (enableLogging) {
            System.out.println("*** Asserting that a fallen behind node was detected ***");
        }
        for (final SyncNode node : nodes) {
            assertEquals(
                    fellBehind,
                    node.getSyncManager().hasFallenBehind(),
                    String.format(
                            "node %s %s have been notified in the sync that it fell behind",
                            node.getNodeId().toString(), fellBehind ? "should" : "should NOT"));
        }
    }

    public static void assertStreamsEmpty(final SyncNode caller, final SyncNode listener) {
        if (enableLogging) {
            System.out.println("*** Asserting streams are empty ***");
        }
        for (final Connection connection : List.of(caller.getConnection(), listener.getConnection())) {
            try {
                assertEquals(0, connection.getDis().available(), "the streams should be empty after a successful sync");
            } catch (final IOException e) {
                fail("Unable to verify the caller or listener stream was empty", e);
            }
        }
    }

    public static void assertExceptionThrown(final SyncNode caller, final SyncNode listener) {
        if (enableLogging) {
            System.out.println("*** Asserting caller and listener threw an exception ***");

            System.out.println("\nCaller Exception: " + caller.getSyncException());
            System.out.println("\nListener Exception: " + listener.getSyncException());
        }
        assertNotNull(caller.getSyncException(), "Expected the caller to have thrown an exception.");
        assertNotNull(listener.getSyncException(), "Expected the listener to have thrown an exception.");
    }

    private static void compareEventLists(
            final SyncNode caller,
            final SyncNode listener,
            final boolean strictCompare,
            @NonNull final AncientMode ancientMode) {
        // Determine the unique events for the caller and listener, since they could have added some of the
        // same events from step 2.
        final Collection<EventImpl> expectedCallerSendList = new ArrayList<>(caller.getGeneratedEvents());
        expectedCallerSendList.removeAll(listener.getGeneratedEvents());

        final Collection<EventImpl> expectedListenerSendList = new ArrayList<>(listener.getGeneratedEvents());
        expectedListenerSendList.removeAll(caller.getGeneratedEvents());

        // Remove expired events
        expectedCallerSendList.removeIf(
                e -> ancientMode.selectIndicator(e.getBaseEvent()) < caller.getExpirationThreshold());
        expectedListenerSendList.removeIf(
                e -> ancientMode.selectIndicator(e.getBaseEvent()) < listener.getExpirationThreshold());

        // Remove events that are ancient for the peer
        expectedCallerSendList.removeIf(
                e -> ancientMode.selectIndicator(e.getBaseEvent()) < listener.getCurrentAncientThreshold());
        expectedListenerSendList.removeIf(
                e -> ancientMode.selectIndicator(e.getBaseEvent()) < caller.getCurrentAncientThreshold());

        // Get the events each received from the other in the sync
        final List<PlatformEvent> callerReceivedEvents = caller.getReceivedEvents();
        final List<PlatformEvent> listenerReceivedEvents = listener.getReceivedEvents();

        if (enableLogging) {
            printEvents("Caller's last added events", caller.getGeneratedEvents());
            printEvents("Listener's last added events", listener.getGeneratedEvents());

            printTipSet("Caller's Tip Set", caller);
            printTipSet("Listener's Tip Set", listener);
            printEvents("expectedCallerSendList", expectedCallerSendList);
            printEvents("expectedListenerSendList", expectedListenerSendList);
            printTasks("caller received", callerReceivedEvents);
            printTasks("listener received", listenerReceivedEvents);
        }

        // Assert that the event each received are the unique events in the other's shadow graph
        compareEventLists("listener", expectedCallerSendList, listener, strictCompare, ancientMode);
        compareEventLists("caller", expectedListenerSendList, caller, strictCompare, ancientMode);
    }

    private static void compareEventLists(
            final String node,
            final Collection<EventImpl> expectedList,
            final SyncNode receiver,
            final boolean strictCompare,
            @NonNull final AncientMode ancientMode) {

        Collection<PlatformEvent> actualList = receiver.getReceivedEvents();

        if (expectedList == null && actualList == null) {
            return;
        }

        if (expectedList == null || actualList == null) {
            fail(format(
                    "Expected and actual mismatch for %s. Expected = %s, Actual = %s", node, expectedList, actualList));
        }

        LinkedList<EventImpl> expectedAndNotFound = new LinkedList<>();

        if (enableLogging) {
            printEvents(format("%s's expected received list", node), expectedList);
            printTasks(format("%s's actual received list", node), actualList);
        }

        // Compare the two lists to see if there is a matching event in the actual list for each expected event
        for (final EventImpl expected : expectedList) {
            boolean foundMatch = false;

            for (final PlatformEvent actual : actualList) {
                if (expected.getBaseEvent().equalsGossipedData(actual)) {
                    foundMatch = true;
                    break;
                }
            }

            if (!foundMatch) {
                expectedAndNotFound.add(expected);
            }
        }

        // Keep track of the number of events we expected to receive but did not
        int numExpectedAndNotFound = expectedAndNotFound.size();
        expectedAndNotFound.removeIf(event -> receiver.getShadowGraph().isHashInGraph(event.getBaseHash()));

        if (!expectedAndNotFound.isEmpty()) {
            List<String> missingHashes = expectedAndNotFound.stream()
                    .map(EventImpl::getBaseHash)
                    .map(h -> h.toHex(4))
                    .collect(Collectors.toList());
            fail(format(
                    "Actual list is missing %s expected event(s) with hash(es) %s",
                    missingHashes.size(), missingHashes));
        }

        // If we made it this far without failing, there were events we expected to receive but did not because they
        // were added to the receiver's graph by another sync. Therefore, the expected number of events is that many
        // less.
        int adjustedExpectedEventNum = expectedList.size() - numExpectedAndNotFound;

        // Ensure that only the events that needed to be sent were sent
        if (strictCompare && adjustedExpectedEventNum != actualList.size()) {
            fail(format("Expected %s events, actual number was %s", expectedList.size(), actualList.size()));
        }
    }

    public static void enableLogging() {
        enableLogging = true;
    }
}
