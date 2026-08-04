// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfoPrev;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo.UpdateResults;

import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/** Class to output a log file for checking the consensus calculations in {@link HashgraphInfo HashgraphInfo}.
 * The filename should be set in OUTPUT_FILE_NAME.
 * It will be written in the log directory, which is in the same directory as this source file.
 *
 * The output CSV file is in the format described by logFormat.md, which is also in this directory.
 */
public class HashgraphInfoTestLogCreate {
    /** name of the file to write */
    private static final String OUTPUT_FILE_NAME = "test.csv";

    /** the ancestor directory to search upward for */
    private static final String REPOSITORY_DIRECTORY_NAME = "hiero-consensus-node";

    /** the directories to descend through, starting at {@link #REPOSITORY_DIRECTORY_NAME} */
    private static final List<String> DESCENT = List.of(
            "platform-sdk",
            "consensus-hashgraph-impl",
            "src",
            "main",
            "java",
            "org",
            "hiero",
            "consensus",
            "hashgraph",
            "impl",
            "consensus",
            "calculations",
            "log");

    /** RoundInfo is a CSV row starting with this number */
    private final static int ROUND_INFO_TYPE = 0;

    /** RoundInfoPrev is a CSV row starting with this number */
    private final static int ROUND_INFO_PREV_TYPE = 1;

    /** EventInfo is a CSV row starting with this number */
    private final static int EVENT_SIGNED_TYPE = 2;

    /** EventInfo is a CSV row starting with this number */
    private final static int EVENT_INFO_TYPE = 3;

    /** UpdateResults is a CSV row starting with this number */
    private final static int UPDATE_RESULTS_TYPE = 4;

    /**
     * Create random hashgraphs, and update the events repeatedly to reach consensus. Write all the results to
     * the CSV (Comma Separated Values) file.
     *
     * This finds that directory by starting where this compiled class is, then doing "cd .." repeatedly until it finds
     * the directory hiero-consensus-node, then going down into the directory
     * hiero-consensus-node/platform-sdk/consensus-hashgraph-impl/src/main/java/
     * org/hiero/consensus/hashgraph/impl/consensus/calculations/log and creates the file there.
     *
     * @param args ignored
     */
    public static void main(final String[] args) {
        final Random random = new Random();


        Path outputFile = outputFilePath();
        if (outputFile == null) {
            return;
        }
        try (final PrintWriter out = new PrintWriter(Files.newBufferedWriter(outputFile))) {
            HashgraphInfo hashgraphInfo = new HashgraphInfo();
            List<EventInfo> recentEvents = new LinkedList<EventInfo>();
            UpdateResults updateResults;
            RoundInfo roundInfo = new RoundInfo(
                    1, //long pendingRound
                    new long[]{100,200,300,400}, //long[] nodes
                    new long[]{101,102,103,104}, //long[] stake
                    10, // int coinInterval
                    3, // int seeNum
                    3, // int seeDen
                    false, // boolean judgeCon1
                    5, // int targetNumRoundsNonAncient
                    2); // int numRoundsAddressBook
            printRoundInfo(out, roundInfo);

            RoundInfoPrev roundInfoPrev = HashgraphInfo.FIRST_ROUND_INFO_PREV;
            printRoundInfoPrev(out, roundInfoPrev);

            EventInfo eventInfo = new EventInfo(
                    hashgraphInfo,  // HashgraphInfo hashgraphInfo
                    1, // long eventID
                    1, // long creator (the creatorID, not the index of the creator used in calculations)
                    Instant.now(),  // Instant timeCreated
                    1, //long birthRound
                    random.nextInt(), // int coin
                    new EventInfo[]{}, // EventInfo[] parents
                    null); // Object payload
            recentEvents.add(eventInfo);
            printEventSigned(out, eventInfo);

            updateResults = eventInfo.update(roundInfo, roundInfoPrev);
            printEventInfo(out, eventInfo);
            if (updateResults != null) {
                printUpdateResults(out, updateResults);
            }

        } catch (Exception e) {
            //final IOException
            System.out.println("ERROR: while writing " + outputFile + " - " + e);
            return;
        }

        System.out.println("wrote " + outputFile);
    }

    /**
     * Find the Path of test log file. This is found by starting where the
     * compiled class is located, and then searching upward until the REPOSITORY_DIRECTORY_NAME directory is found,
     * then searching back downward through the directories in DESCENT.
     *
     * @return the Path, or null if anything went wrong, in which case an error has already been printed
     */
    private static Path outputFilePath() {
        //Find the directory holding the compiled class, which is where the upward search starts.
        // This is independent of the working directory the JVM happens to be launched from.
        final CodeSource codeSource = HashgraphInfoTestLogCreate.class.getProtectionDomain().getCodeSource();
        final URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null) {
            System.out.println("ERROR: the location of the compiled class is unavailable - no file was written");
            return null;
        }

        final Path path;
        try {
            path = Path.of(location.toURI());
        } catch (final URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            System.out.println("ERROR: the compiled class is not at a local file path: " + location
                    + " - no file was written");
            return null;
        }

        // the location is a directory of class files, or a jar, in which case start at its parent
        final Path start = Files.isDirectory(path) ? path : path.getParent();
        if (start == null) {
            return null;
        }

        // walk up to the repository directory
        Path directory = start;
        while (directory != null
                && !REPOSITORY_DIRECTORY_NAME.equals(String.valueOf(directory.getFileName()))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            System.out.println("ERROR: no ancestor directory named \"" + REPOSITORY_DIRECTORY_NAME
                    + "\" was found above " + start + " - no file was written");
            return null;
        }

        // walk back down, checking each directory exists
        for (final String name : DESCENT) {
            directory = directory.resolve(name);
            if (!Files.isDirectory(directory)) {
                System.out.println("ERROR: directory does not exist: " + directory + " - no file was written");
                return null;
            }
        }

        // if no errors so far, then create the file here.
        return directory.resolve(OUTPUT_FILE_NAME);
    }

    /** return one line of the CSV file describing the RoundInfo fields */
    private static void printRoundInfo(PrintWriter out, RoundInfo roundInfo) {
        StringBuilder line = new StringBuilder();
        line.append(ROUND_INFO_TYPE).append(",")
                .append(roundInfo.pendingRound());
        out.println(line);
    }

    /** return one line of the CSV file describing the RoundInfoPrev fields */
    private static void printRoundInfoPrev(PrintWriter out, RoundInfoPrev roundInfoPrev) {
        StringBuilder line = new StringBuilder();
        line.append(ROUND_INFO_PREV_TYPE).append(",")
                .append(roundInfoPrev.pendingRound());
        out.println(line);
    }

    /** return one line of the CSV file describing the EventInfo fields in the signed events, before updating */
    private static void printEventSigned(PrintWriter out, EventInfo eventInfo) {
        StringBuilder line = new StringBuilder();
        line.append(EVENT_SIGNED_TYPE).append(",")
                .append(eventInfo.getEventID()).append(",");
        appendEvents(line,eventInfo.getParentsSigned());
        out.println(line);
    }

    /** return one line of the CSV file describing the given EventInfo with calculated memoized fields */
    private static void printEventInfo(PrintWriter out, EventInfo eventInfo) {
        StringBuilder line = new StringBuilder();
        line.append(EVENT_INFO_TYPE).append(",")
                .append(eventInfo.getEventID());
        out.println(line);
    }

    /** return one line of the CSV file describing the given EventInfo with calculated memoized fields */
    private static void printUpdateResults(PrintWriter out, UpdateResults updateResults) {
        StringBuilder line = new StringBuilder();
        line.append(UPDATE_RESULTS_TYPE).append(",");
        appendEvents(line,updateResults.consensusEvents());
        out.println(line);
    }

    /** append to the CSV line an array of events (length then each eventID) with no comma before or after */
    private static void appendEvents(StringBuilder line, EventInfo[] events) {
        line.append(events.length);
        for (EventInfo event : events) {
            line.append(",").append(event.getEventID());
        }
    }
}