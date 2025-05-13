// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats;
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

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.gui.SwirldsGui.createConsole;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.Console;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Browser;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.NoOpConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;

/**
 * This demo collects statistics on the running of the network and consensus systems. It writes them to the
 * screen, and also saves them to disk in a comma separated value (.csv) file. Each transaction is 100
 * random bytes. So StatsDemoState.handleTransaction doesn't actually do anything.
 */
public class StatsDemoMain implements SwirldMain<StatsDemoState> {
    // the first four come from the parameters in the config.txt file

    /** should this run with no windows? */
    private boolean headless = false;
    /** number of milliseconds between writes to the log file */
    private int writePeriod = 3000;
    /** bytes in each transaction */
    private int bytesPerTrans = 1;
    /** create at most this many transactions in preEvent, even if more is needed to meet target rate */
    private int transPerEventMax = 2048;
    /** transactions in each Event */
    private int transPerSecToCreate = 100;

    /** path and filename of the .csv file to write to */
    private String path;
    /** ID number for this member */
    private NodeId selfId;
    /** the app is run by this */
    private Platform platform;
    /** a console window for text output */
    private Console console = null;
    /** used to make the transactions random, so they won't cheat and shrink when zipped */
    private Random random = new java.util.Random();

    private static final SemanticVersion semanticVersion =
            SemanticVersion.newBuilder().major(1).build();

    static {
        try {
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(StatsDemoState.class, () -> {
                StatsDemoState statsDemoState = new StatsDemoState();
                return statsDemoState;
            }));
            registerMerkleStateRootClassIds();
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    private final StoppableThread transactionGenerator;

    public StatsDemoMain() {
        transactionGenerator = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("demo")
                .setThreadName("transaction-generator")
                .setMaximumRate(50)
                .setWork(this::generateTransactions)
                .build();
    }

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

    /**
     * Write a message to the log file. Also write it to the console, if there is one. In both cases, skip a
     * line after writing, if newline is true. This method opens the file at the start and closes it at the
     * end, to deconflict with any other process trying to read the same file. For example, this app could
     * run headless on a server, and an FTP session could download the log file, and the file it received
     * would have only complete log messages, never half a message.
     * <p>
     * The file is created if it doesn't exist. It will be named "StatsDemo0.csv", with the number
     * incrementing for each member currently running on the local machine, if there is more than one. The
     * location is the "current" directory. If run from a shell script, it will be the current folder that
     * the shell script has. If run from Eclipse, it will be at the top of the project folder. If there is a
     * console, it prints the location there. If not, it can be found by searching the file system for
     * "StatsDemo0.csv".
     *
     * @param message
     * 		the String to write
     * @param newline
     * 		true if a new line should be started after this one
     */
    private void write(String message, boolean newline) {
        path = System.getProperty("user.dir") + File.separator + "StatsDemo" + selfId + ".csv";
        // create or append to file in current directory
        try (BufferedWriter file = new BufferedWriter(new FileWriter(path, true))) {
            if (newline) {
                file.write("\n");
            } else {
                file.write(message.trim().replaceAll(",", "") + ",");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (console != null) {
            console.out.print(newline ? "\n" : message);
        }
    }

    /** Erase the existing file (if one exists) */
    private void eraseFile() {
        path = System.getProperty("user.dir") + File.separator + "StatsDemo" + selfId + ".csv";
        // erase file in current directory
        try (BufferedWriter file = new BufferedWriter(new FileWriter(path, false))) {
            file.write("");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Same as writeToConsolAndFile, except it does not start a new line after it.
     *
     * @param message
     * 		the String to write
     */
    private void write(String message) {
        write(message, false);
    }

    /** Start the next line, for both console and file. */
    private void newline() {
        write("", true);
    }

    /////////////////////////////////////////////////////////////////////
    /** the time of the last call to preEvent */
    long lastEventTime = System.nanoTime();
    /** number of events needed to be created (the non-integer leftover from last preEvent call */
    double toCreate = 0;

    private synchronized void generateTransactions() {
        byte[] transaction = new byte[bytesPerTrans];
        long now = System.nanoTime();
        double tps = transPerSecToCreate / platform.getRoster().rosterEntries().size();
        int numCreated = 0;

        if (transPerSecToCreate > -1) { // if not unlimited (-1 means unlimited)
            toCreate += ((double) now - lastEventTime) * NANOSECONDS_TO_SECONDS * tps;
        }
        lastEventTime = now;
        while (true) {
            if (transPerSecToCreate > -1 && toCreate < 1) {
                break; // don't create too many transactions per second
            }
            if (transPerEventMax > -1 && numCreated >= transPerEventMax) {
                break; // don't create too many transactions per event
            }
            random.nextBytes(transaction); // random, so it's non-compressible
            if (!platform.createTransaction(transaction)) {
                break; // if the queue is full, the stop adding to it
            }
            numCreated++;
            toCreate--;
        }
        // toCreate will now represent any leftover transactions that we
        // failed to create this time, and will create next time
    }

    @Override
    public void init(final Platform platform, final NodeId id) {

        this.platform = platform;
        selfId = id;
        // parse the config.txt parameters, and allow optional _ as in 1_000_000
        final String[] parameters = ParameterProvider.getInstance().getParameters();
        headless = (parameters[0].equals("1"));
        writePeriod = Integer.parseInt(parameters[1].replaceAll("_", ""));
        bytesPerTrans = Integer.parseInt(parameters[3].replaceAll("_", ""));
        transPerEventMax = Integer.parseInt(parameters[4].replaceAll("_", ""));
        transPerSecToCreate = Integer.parseInt(parameters[5].replaceAll("_", ""));
        if (transPerEventMax == -1 && transPerSecToCreate == -1) {
            // they shouldn't both be -1, so set one of them
            transPerEventMax = 1024;
        }
        if (!headless) { // create the window, make it visible
            final int winNum = (int) selfId.id();
            console = createConsole(platform, winNum, true);
        }
    }

    @Override
    public void run() {
        final Metrics metrics = platform.getContext().getMetrics();
        // create a copy of the currently registered metrics
        final List<Metric> metricList = metrics.getAll().stream().toList();

        // erase the old file, if any
        eraseFile();

        // set the heading at the top of the console
        if (console != null) {
            final StringBuilder builder = new StringBuilder();
            for (final Metric metric : metricList) {
                final String formatString = "%" + valueAsString(metric).length() + "s";
                builder.append(String.format(formatString, metric.getName()));
            }
            console.setHeading(builder + "\n");
        }

        // write the definitions at the top (name is stats[i][0], description is stats[i][1])
        write(String.format("%14s: ", "filename"));
        write(String.format("%s", path));
        newline();
        for (final Metric metric : metricList) {
            write(String.format("%14s: ", metric.getName()));
            write(String.format("%s", metric.getDescription()));
            newline();
        }
        newline();

        // write the column headings again
        write(""); // indent by two columns
        write("");
        for (final Metric metric : metricList) {
            final String formatString = "%" + valueAsString(metric).length() + "s";
            write(String.format(formatString, metric.getName()));
        }
        newline();

        transactionGenerator.start();

        while (true) { // keep logging forever
            try {
                // write a row of numbers
                write("");
                write("");
                for (final Metric metric : metricList) {
                    write(valueAsString(metric));
                }
                newline();
                Thread.sleep(writePeriod); // add new rows infrequently
            } catch (InterruptedException e) {
            }
        }
    }

    private String valueAsString(final Metric metric) {
        try {
            return String.format(Locale.US, metric.getFormat(), metric.get(ValueType.VALUE));
        } catch (final IllegalFormatException e) {
            if (console != null) {
                console.out.println(e.getLocalizedMessage());
            }
        }
        return "";
    }

    @NonNull
    @Override
    public StatsDemoState newStateRoot() {
        final StatsDemoState state = new StatsDemoState();
        TestingAppStateInitializer.DEFAULT.initStates(state);
        return state;
    }

    @NonNull
    @Override
    public ConsensusStateEventHandler newConsensusStateEvenHandler() {
        return NoOpConsensusStateEventHandler.NO_OP_CONSENSUS_STATE_EVENT_HANDLER;
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
