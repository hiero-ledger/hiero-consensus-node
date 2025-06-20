// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.components.common.output.FatalErrorConsumer;
import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.iss.internal.DefaultIssHandler;
import com.swirlds.platform.test.fixtures.SimpleScratchpad;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IssHandler Tests")
class IssHandlerTests {
    @Test
    @DisplayName("Other ISS Always Freeze")
    void otherIssAlwaysFreeze() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, true)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.OTHER_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        // Once frozen, this should become a no-op
        handler.issObserved(new IssNotification(1234L, IssType.OTHER_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        // Another node ISSed, we will not record that on the scratchpad.
        assertNull(simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND));
    }

    @Test
    @DisplayName("Other ISS No Action")
    void otherIssNoAction() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.OTHER_ISS));

        assertEquals(0, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        assertNull(simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND));
    }

    @Test
    @DisplayName("Self ISS Automated Recovery")
    void selfIssAutomatedRecovery() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, false)
                .withValue(StateConfig_.AUTOMATED_SELF_ISS_RECOVERY, true)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.SELF_ISS));

        assertEquals(0, freezeCount.get(), "unexpected freeze count");
        assertEquals(1, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }

    @Test
    @DisplayName("Self ISS No Action")
    void selfIssNoAction() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, false)
                .withValue(StateConfig_.AUTOMATED_SELF_ISS_RECOVERY, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.SELF_ISS));

        assertEquals(0, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }

    @Test
    @DisplayName("Self ISS Always Freeze")
    void selfIssAlwaysFreeze() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, true)
                .withValue(StateConfig_.AUTOMATED_SELF_ISS_RECOVERY, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.SELF_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        // Once frozen, this should become a no-op
        handler.issObserved(new IssNotification(1234L, IssType.SELF_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }

    @Test
    @DisplayName("Catastrophic ISS No Action")
    void catastrophicIssNoAction() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, false)
                .withValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.CATASTROPHIC_ISS));

        assertEquals(0, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }

    @Test
    @DisplayName("Catastrophic ISS Always Freeze")
    void catastrophicIssAlwaysFreeze() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, true)
                .withValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.CATASTROPHIC_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        // Once frozen, this should become a no-op
        handler.issObserved(new IssNotification(1234L, IssType.CATASTROPHIC_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }

    @Test
    @DisplayName("Catastrophic ISS Freeze On Catastrophic")
    void catastrophicIssFreezeOnCatastrophic() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.HALT_ON_ANY_ISS, false)
                .withValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final AtomicInteger freezeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();

        final Consumer<String> haltRequestedConsumer = (final String reason) -> freezeCount.getAndIncrement();

        final FatalErrorConsumer fatalErrorConsumer = (msg, t, code) -> shutdownCount.getAndIncrement();

        final Scratchpad<IssScratchpad> simpleScratchpad = new SimpleScratchpad<>();
        final IssHandler handler =
                new DefaultIssHandler(platformContext, haltRequestedConsumer, fatalErrorConsumer, simpleScratchpad);

        handler.issObserved(new IssNotification(1234L, IssType.CATASTROPHIC_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        // Once frozen, this should become a no-op
        handler.issObserved(new IssNotification(1234L, IssType.CATASTROPHIC_ISS));

        assertEquals(1, freezeCount.get(), "unexpected freeze count");
        assertEquals(0, shutdownCount.get(), "unexpected shutdown count");

        final SerializableLong issRound = simpleScratchpad.get(IssScratchpad.LAST_ISS_ROUND);
        assertNotNull(issRound);
        assertEquals(1234L, issRound.getValue());
    }
}
