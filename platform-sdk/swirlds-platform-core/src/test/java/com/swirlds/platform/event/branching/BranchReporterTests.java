// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import static com.swirlds.platform.event.branching.BranchDetectorTests.generateSimpleSequenceOfEvents;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.Test;

/**
 * It's currently difficult to write unit tests to validate metrics and logging. The least we can do is ensure that it
 * doesn't throw an exception.
 */
class BranchReporterTests {

    @Test
    void doesNotThrowSmallAncientWindow() {

        final Randotron randotron = Randotron.create();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, roster);

        int ancientThreshold = randotron.nextInt(1, 1000);
        reporter.updateEventWindow(EventWindowBuilder.builder()
                .setAncientThreshold(ancientThreshold)
                .build());

        final List<PlatformEvent> events = new ArrayList<>();
        for (final NodeId nodeId : roster.rosterEntries().stream()
                .map(re -> NodeId.of(re.nodeId()))
                .toList()) {
            events.addAll(generateSimpleSequenceOfEvents(randotron, nodeId, ancientThreshold, 512));
        }

        for (final PlatformEvent event : events) {
            reporter.reportBranch(event);

            if (randotron.nextBoolean(0.1)) {
                ancientThreshold++;
                reporter.updateEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(ancientThreshold)
                        .build());
            }
            if (randotron.nextBoolean(0.1)) {
                reporter.clear();
                reporter.updateEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(ancientThreshold)
                        .build());
            }
        }

        // Advance ancient window very far into the future
        ancientThreshold += 1000;
        reporter.updateEventWindow(EventWindowBuilder.builder()
                .setAncientThreshold(ancientThreshold)
                .build());
    }

    @Test
    void doesNotThrowLargeAncientWindow() {
        final Randotron randotron = Randotron.create();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, roster);

        int ancientThreshold = randotron.nextInt(1, 1000);
        reporter.updateEventWindow(EventWindowBuilder.builder()
                .setAncientThreshold(ancientThreshold)
                .build());

        final List<PlatformEvent> events = new ArrayList<>();
        for (final NodeId nodeId : roster.rosterEntries().stream()
                .map(re -> NodeId.of(re.nodeId()))
                .toList()) {
            events.addAll(generateSimpleSequenceOfEvents(randotron, nodeId, ancientThreshold, 512));
        }

        for (final PlatformEvent event : events) {
            reporter.reportBranch(event);

            if (randotron.nextBoolean(0.01)) {
                ancientThreshold++;
                reporter.updateEventWindow(EventWindowBuilder.builder()
                        .setAncientThreshold(ancientThreshold)
                        .build());
            }
        }

        // Advance ancient window very far into the future
        ancientThreshold += 1000;
        reporter.updateEventWindow(EventWindowBuilder.builder()
                .setAncientThreshold(ancientThreshold)
                .build());
    }

    @Test
    void eventWindowMustBeSetTest() {
        final Randotron randotron = Randotron.create();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(8).build();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final DefaultBranchReporter reporter = new DefaultBranchReporter(platformContext, roster);

        final PlatformEvent event = new TestingEventBuilder(randotron)
                .setCreatorId(NodeId.of(roster.rosterEntries().get(0).nodeId()))
                .build();
        assertThrows(IllegalStateException.class, () -> reporter.reportBranch(event));
    }
}
