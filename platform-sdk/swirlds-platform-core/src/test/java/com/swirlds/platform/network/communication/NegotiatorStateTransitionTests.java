// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import static com.swirlds.platform.network.communication.NegotiatorBytes.ACCEPT;
import static com.swirlds.platform.network.communication.NegotiatorBytes.KEEPALIVE;
import static com.swirlds.platform.network.communication.NegotiatorBytes.REJECT;
import static com.swirlds.platform.network.communication.NegotiatorTestSuite.PROTOCOL_1;
import static com.swirlds.platform.network.communication.NegotiatorTestSuite.PROTOCOL_2;
import static com.swirlds.platform.network.communication.NegotiatorTestSuite.PROTOCOL_3;

import com.swirlds.platform.network.NetworkProtocolException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Each test specifies the input to feed into the negotiator and the output that is expected to be returned. Each test
 * is repeated, and they all run on the same negotiator. The purpose of this is to test if the negotiator is returning to
 * the initial state after the previous test, which it should.
 */
class NegotiatorStateTransitionTests {
    private static final int REPETITIONS = 3;
    private static final NegotiatorTestSuite suite = new NegotiatorTestSuite();

    /**
     * Resets the test setup, but does not reset the negotiator
     */
    @AfterEach
    void reset() {
        suite.reset();
    }

    @DisplayName("Send a KEEPALIVE and expect to receive the same")
    @RepeatedTest(REPETITIONS)
    void bothKeepalive() throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        suite.input(KEEPALIVE);
        suite.expectedOutput(KEEPALIVE);
        suite.execute();
        suite.assertProtocolRuns(0, 0, 0);
    }

    @DisplayName("Initiate protocols 2 & 3, expect the negotiator to reject 2 and accept and run 3")
    @RepeatedTest(REPETITIONS)
    void sentKeepaliveRecInit()
            throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        suite.getProtocol(PROTOCOL_2).setShouldAccept(false);
        suite.getProtocol(PROTOCOL_3).setShouldAccept(true);
        suite.input(PROTOCOL_2, PROTOCOL_3);
        suite.expectedOutput(KEEPALIVE, REJECT, KEEPALIVE, ACCEPT);
        suite.execute();
        suite.assertProtocolRuns(0, 0, 0);
        suite.execute();
        suite.assertProtocolRuns(0, 0, 1);
    }

    @DisplayName("Send a keepalive, expect the negotiator to initiate protocol 2, and run it when accepted")
    @RepeatedTest(REPETITIONS)
    void sentInitRecKeepalive()
            throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        suite.getProtocol(PROTOCOL_2).setShouldInitiate(true);
        suite.input(KEEPALIVE, ACCEPT, KEEPALIVE, REJECT);
        suite.expectedOutput(PROTOCOL_2, PROTOCOL_2);
        suite.execute();
        suite.execute();
        suite.assertProtocolRuns(0, 1, 0);
    }

    @DisplayName("Expect a protocol to run when initiated simultaneously by both parties")
    @RepeatedTest(REPETITIONS)
    void simInitiateRun() throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        final int protocolInitiated = PROTOCOL_1;
        suite.getProtocol(protocolInitiated).setShouldInitiate(true).setAcceptOnSimultaneousInitiate(true);
        suite.input(protocolInitiated);
        suite.expectedOutput(protocolInitiated);
        suite.execute();
        suite.assertProtocolRuns(1, 0, 0);
    }

    @DisplayName("Expect a protocol to NOT run when initiated simultaneously by both parties")
    @RepeatedTest(REPETITIONS)
    void simInitiateDontRun() throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        final int protocolInitiated = PROTOCOL_1;
        suite.getProtocol(protocolInitiated).setShouldInitiate(true).setAcceptOnSimultaneousInitiate(false);
        suite.input(protocolInitiated);
        suite.expectedOutput(protocolInitiated);
        suite.execute();
        suite.assertProtocolRuns(0, 0, 0);
    }

    @DisplayName("Two different protocols are initiated simultaneously, test initiated a lower priority protocol")
    @RepeatedTest(REPETITIONS)
    void simInitiateInputLowerPriority()
            throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        suite.getProtocol(PROTOCOL_1).setShouldInitiate(true);
        suite.getProtocol(PROTOCOL_2).setShouldAccept(true);
        suite.input(PROTOCOL_2, ACCEPT);
        suite.expectedOutput(PROTOCOL_1);
        suite.execute();
        suite.assertProtocolRuns(1, 0, 0);
    }

    @DisplayName("Two different protocols are initiated simultaneously,  test initiated a higher priority protocol")
    @RepeatedTest(REPETITIONS)
    void simInitiateInputHigherPriority()
            throws NetworkProtocolException, NegotiationException, IOException, InterruptedException {
        suite.getProtocol(PROTOCOL_2).setShouldAccept(true);
        suite.getProtocol(PROTOCOL_3).setShouldInitiate(true);
        suite.input(PROTOCOL_2);
        suite.expectedOutput(PROTOCOL_3, ACCEPT);
        suite.execute();
        suite.assertProtocolRuns(0, 1, 0);
    }
}
