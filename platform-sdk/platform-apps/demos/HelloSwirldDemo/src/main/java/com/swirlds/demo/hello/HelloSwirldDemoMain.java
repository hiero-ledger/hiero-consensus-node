// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.hello;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import static com.swirlds.platform.gui.SwirldsGui.createConsole;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.Console;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.platform.Browser;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * This HelloSwirld creates a single transaction, consisting of the string "Hello Swirld", and then goes
 * into a busy loop (checking once a second) to see when the state gets the transaction. When it does, it
 * prints it, too.
 */
public class HelloSwirldDemoMain implements SwirldMain<HelloSwirldDemoState> {

    static {
        try {
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(HelloSwirldDemoState.class, () -> {
                HelloSwirldDemoState helloSwirldDemoState = new HelloSwirldDemoState();
                return helloSwirldDemoState;
            }));
            registerMerkleStateRootClassIds();
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /** the platform running this app */
    public SwirldsPlatform platform;
    /** ID number for this member */
    public NodeId selfId;
    /** a console window for text output */
    public Console console;
    /** sleep this many milliseconds after each sync */
    public final int sleepPeriod = 100;

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    /**
     * This is just for debugging: it allows the app to run in Eclipse. If the config.txt exists and lists a
     * particular SwirldMain class as the one to run, then it can run in Eclipse (with the green triangle
     * icon).
     *
     * @param args
     * 		these are not used
     */
    public static void main(String[] args) {
        Browser.parseCommandLineArgsAndLaunch(args);
    }

    @Override
    public void init(final Platform platform, final NodeId id) {

        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, this::platformStatusChange);

        this.platform = (SwirldsPlatform) platform;
        this.selfId = id;
        final int winNum = (int) selfId.id();
        this.console = createConsole(platform, winNum, true); // create the window, make it visible
    }

    @Override
    public void run() {
        String lastReceived = "";

        while (true) {
            String received;
            try (final AutoCloseableWrapper<HelloSwirldDemoState> wrapper =
                    platform.getLatestImmutableState("HelloSwirldDemoMain.run()")) {
                final HelloSwirldDemoState state = wrapper.get();
                received = state.getReceived();
            }

            if (!lastReceived.equals(received)) {
                lastReceived = received;
                console.out.println("Received: " + received); // print all received transactions
            }
            try {
                Thread.sleep(sleepPeriod);
            } catch (Exception e) {
            }
        }
    }

    @NonNull
    @Override
    public HelloSwirldDemoState newStateRoot() {
        final HelloSwirldDemoState state = new HelloSwirldDemoState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
        return state;
    }

    @NonNull
    @Override
    public ConsensusStateEventHandler<HelloSwirldDemoState> newConsensusStateEvenHandler() {
        return new HelloSwirldDemoConsensusStateEventHandler();
    }

    private void platformStatusChange(final PlatformStatusChangeNotification notification) {
        final PlatformStatus newStatus = notification.getNewStatus();
        if (PlatformStatus.ACTIVE.equals(newStatus)) {
            final String myName =
                    RosterUtils.formatNodeName(platform.getSelfId().id());

            console.out.println("Hello Swirld from " + myName);

            // create a transaction. For this example app,
            // we will define each transactions to simply
            // be a string in UTF-8 encoding.
            byte[] transaction = myName.getBytes(StandardCharsets.UTF_8);

            // Send the transaction to the Platform, which will then
            // forward it to the State object.
            // The Platform will also send the transaction to
            // all the other members of the community during syncs with them.
            // The community as a whole will decide the order of the transactions
            platform.createTransaction(transaction);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return semanticVersion;
    }

    @Override
    public Bytes encodeSystemTransaction(@NonNull StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
