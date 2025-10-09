// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.hashlogger;

import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.MerkleNodeState;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.MessageSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HashLoggerTest {
    private Logger mockLogger;
    private HashLogger hashLogger;
    private List<String> logged;
    private TestPlatformStateFacade platformStateFacade;

    /**
     * Get a regex that will match a log message containing the given round number
     *
     * @param round the round number
     * @return the regex
     */
    private String getRoundEqualsRegex(final long round) {
        return String.format("State Info, round = %s[\\S\\s]*", round);
    }

    @BeforeEach
    public void setUp() {
        mockLogger = mock(Logger.class);
        platformStateFacade = mock(TestPlatformStateFacade.class);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        hashLogger = new DefaultHashLogger(platformContext, mockLogger, platformStateFacade);
        logged = new ArrayList<>();

        doAnswer(invocation -> {
                    final MessageSupplier supplier = invocation.getArgument(1, MessageSupplier.class);
                    final String message = supplier.get().getFormattedMessage();
                    logged.add(message);
                    return message;
                })
                .when(mockLogger)
                .info(eq(STATE_HASH.getMarker()), any(MessageSupplier.class));
    }

    @Test
    public void loggingInOrder() {
        hashLogger.logHashes(createSignedState(1));
        hashLogger.logHashes(createSignedState(2));
        hashLogger.logHashes(createSignedState(3));
        assertThat(logged).hasSize(3);
        assertThat(logged.get(0)).matches(getRoundEqualsRegex(1));
        assertThat(logged.get(1)).matches(getRoundEqualsRegex(2));
        assertThat(logged.get(2)).matches(getRoundEqualsRegex(3));
    }

    @Test
    public void loggingEarlierEventsDropped() {
        hashLogger.logHashes(createSignedState(1));
        hashLogger.logHashes(createSignedState(2));
        hashLogger.logHashes(createSignedState(3));
        hashLogger.logHashes(createSignedState(1));
        hashLogger.logHashes(createSignedState(1));
        hashLogger.logHashes(createSignedState(2));
        assertThat(logged).hasSize(3);
        assertThat(logged.get(0)).matches(getRoundEqualsRegex(1));
        assertThat(logged.get(1)).matches(getRoundEqualsRegex(2));
        assertThat(logged.get(2)).matches(getRoundEqualsRegex(3));
    }

    @Test
    public void loggingWithGapsAddsExtraWarning() {
        hashLogger.logHashes(createSignedState(1));
        hashLogger.logHashes(createSignedState(2));
        hashLogger.logHashes(createSignedState(5));
        hashLogger.logHashes(createSignedState(4));
        assertThat(logged).hasSize(4);
        assertThat(logged.get(0)).matches(getRoundEqualsRegex(1));
        assertThat(logged.get(1)).matches(getRoundEqualsRegex(2));
        assertThat(logged.get(2)).contains("Several rounds skipped. Round received 5. Previously received 2.");
        assertThat(logged.get(3)).matches(getRoundEqualsRegex(5));
    }

    @Test
    public void noLoggingWhenDisabled() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.ENABLE_HASH_STREAM_LOGGING, false)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        hashLogger = new DefaultHashLogger(platformContext, mockLogger, platformStateFacade);
        hashLogger.logHashes(createSignedState(1));
        assertThat(logged).isEmpty();
    }

    @Test
    public void loggerWithDefaultConstructorWorks() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final PlatformStateFacade platformStateFacade = mock(PlatformStateFacade.class);

        assertDoesNotThrow(() -> {
            hashLogger = new DefaultHashLogger(platformContext, platformStateFacade);
            hashLogger.logHashes(createSignedState(1));
        });
    }

    private ReservedSignedState createSignedState(final long round) {
        final MerkleNode merkleNode = MerkleTestUtils.buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(merkleNode);
        final SignedState signedState = mock(SignedState.class);
        final MerkleNodeState state = mock(MerkleNodeState.class);
        final MerkleNode stateRoot = mock(MerkleNode.class);
        final PlatformStateAccessor platformState = mock(PlatformStateAccessor.class);
        when(platformState.getRound()).thenReturn(round);
        when(state.getRoot()).thenReturn(stateRoot);
        when(stateRoot.getRoute()).thenReturn(merkleNode.getRoute());
        when(state.getHash()).thenReturn(merkleNode.getHash());

        when(signedState.getState()).thenReturn(state);
        when(signedState.getRound()).thenReturn(round);

        ReservedSignedState reservedSignedState = mock(ReservedSignedState.class);
        when(reservedSignedState.get()).thenReturn(signedState);
        when(signedState.reserve(anyString())).thenReturn(reservedSignedState);

        return signedState.reserve("hash logger test");
    }
}
