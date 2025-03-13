package com.swirlds.platform.test.fixtures.event.emitter;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSourceFactory;

public class EventEmitterCreator {

    public static StandardEventEmitter newStandardEmitter(final long randomSeed, final int numNodes) {
        final Randotron random = Randotron.create(randomSeed);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Roster roster = RandomRosterBuilder.create(random).withSize(numNodes).build();

        final EventSourceFactory eventSourceFactory = new EventSourceFactory(numNodes);

        return new StandardEventEmitter(new StandardGraphGenerator(
                platformContext,
                randomSeed,
                eventSourceFactory.generateSources(),
                roster));
    }
}
