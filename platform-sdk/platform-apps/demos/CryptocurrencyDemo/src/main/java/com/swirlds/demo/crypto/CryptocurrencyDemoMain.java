// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.crypto;
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

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.gui.SwirldsGui.createConsole;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.Console;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Browser;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;

/**
 * This demonstrates a cryptocurrency and a stock market. There are 10 stocks, and each member repeatedly generates an
 * ask or a bid on a stock, offering to sell or buy, respectively, a single share at a random price between 1 and 127
 * cents (inclusive).
 */
public class CryptocurrencyDemoMain implements SwirldMain<CryptocurrencyDemoState> {

    static {
        try {
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(CryptocurrencyDemoState.class, () -> {
                CryptocurrencyDemoState cryptocurrencyDemoState = new CryptocurrencyDemoState();
                return cryptocurrencyDemoState;
            }));
            registerMerkleStateRootClassIds();
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /** time to delay between screen updates, in milliseconds (250 for 4 times a second) */
    private final long screenUpdateDelay = 250;
    /** the app is run by this */
    private Platform platform;
    /** ID for this member */
    private NodeId selfId;
    /** a console window for text output */
    private Console console;
    /** used to randomly choose ask/bid and prices */
    private Random rand = new Random();
    /** so user can use arrows and spacebar */
    private GuiKeyListener keyListener = new GuiKeyListener();
    /** if not -1, then need to create a transaction to sync fast or slow */
    private byte speedCmd = -1;
    /** is the simulation running fast now? */
    private boolean isFast = false;

    private static final BasicSoftwareVersion softwareVersion = new BasicSoftwareVersion(1);
    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    private final StoppableThread transactionGenerator;

    public CryptocurrencyDemoMain() {
        transactionGenerator = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("demo")
                .setThreadName("transaction-generator")
                .setMaximumRate(4)
                .setWork(this::generateTransactions)
                .build();
    }

    /** Listen for input from the keyboard, and remember the last key typed. */
    private class GuiKeyListener implements KeyListener {
        @Override
        public void keyReleased(KeyEvent e) {}

        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyChar() == 'F' || e.getKeyChar() == 'f') {
                isFast = true;
                speedCmd = (byte) CryptocurrencyDemoState.TransType.fast.ordinal();
            } else if (e.getKeyChar() == 'S' || e.getKeyChar() == 's') {
                isFast = false;
                speedCmd = (byte) CryptocurrencyDemoState.TransType.slow.ordinal();
            }
        }

        @Override
        public void keyTyped(KeyEvent e) {}
    }

    /**
     * This is just for debugging: it allows the app to run in Eclipse. If the config.txt exists and lists a particular
     * SwirldMain class as the one to run, then it can run in Eclipse (with the green triangle icon).
     *
     * @param args these are not used
     */
    public static void main(String[] args) {
        Browser.parseCommandLineArgsAndLaunch(args);
    }

    private void generateTransactions() {
        int numStocks = CryptocurrencyDemoState.NUM_STOCKS;
        byte[] price = new byte[numStocks];
        try (final AutoCloseableWrapper<CryptocurrencyDemoState> wrapper =
                platform.getLatestImmutableState("CryptocurrencyDemoMain.generateTransactions()")) {
            final CryptocurrencyDemoState state = wrapper.get();
            state.getPriceCopy(price);
        }
        // create one transaction for each stock, to try to buy or sell it
        for (int i = 0; i < numStocks; i++) {
            if (Math.random() < 0.5) {
                byte askBid = (byte)
                        (rand.nextInt(2) == 0 // randomly choose either ask or bid
                                ? CryptocurrencyDemoState.TransType.ask.ordinal()
                                : CryptocurrencyDemoState.TransType.bid.ordinal());
                byte cents = (byte) Math.max(
                        0, // ask or bid a price close to the current price
                        Math.min(127, price[i] + rand.nextInt(5) - 2));
                platform.createTransaction(new byte[] {askBid, (byte) i, cents});
            }
        }
        if (speedCmd != -1) {
            platform.createTransaction(new byte[] {speedCmd});
            speedCmd = -1;
        }
    }

    @Override
    public void init(final Platform platform, final NodeId id) {
        this.platform = platform;
        this.selfId = id;
        final int winNum = (int) selfId.id();
        this.console = createConsole(platform, winNum, true); // create the window, make it visible
        this.console.addKeyListener(keyListener);
    }

    @Override
    public void run() {
        long seq = 0;
        this.transactionGenerator.start();
        // print the latest trades to the console, 4 times a second, forever
        while (true) {
            try (final AutoCloseableWrapper<CryptocurrencyDemoState> wrapper =
                    platform.getLatestImmutableState("CryptocurrencyDemoMain.run()")) {
                final CryptocurrencyDemoState state = wrapper.get();
                console.setHeading(" Cryptocurrency and Stock Market Demo\n"
                        + " Press F for fast sync, S for slow, (currently "
                        + (isFast ? "fast" : "slow") + ")\n"
                        + String.format(
                                " %s",
                                platform.getContext().getMetrics().getValue(Metrics.PLATFORM_CATEGORY, "trans_per_sec"))
                        + " transactions per second for member " + selfId + "\n\n"
                        + " count  ticker  price change  change%  seller->buyer");
                long lastSeq = state.getNumTrades();
                for (; seq < lastSeq; seq++) {
                    String s = state.getTrade(seq);
                    if (!s.equals("")) {
                        console.out.println(s);
                    }
                }
            }
            try {
                Thread.sleep(screenUpdateDelay);
            } catch (Exception e) {
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public CryptocurrencyDemoState newStateRoot() {
        final CryptocurrencyDemoState state = new CryptocurrencyDemoState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConsensusStateEventHandler<CryptocurrencyDemoState> newConsensusStateEvenHandler() {
        return new CryptocurrencyDemoConsensusStateEventHandler();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BasicSoftwareVersion getSoftwareVersion() {
        return softwareVersion;
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
