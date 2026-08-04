// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfoPrev;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo.UpdateResults;

import java.io.PrintWriter;
import java.io.StringWriter;
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
 * The filename should be set in OUTPUT_FILE_NAME. It will be written in the same directory as this source
 * file (if this file is at location DESCENT).
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
            "calculations");

    /** RoundInfoPrev is a CSV row starting with this number */
    private final static int ROUND_INFO_PREV_TYPE = 0;

    /** RoundInfo is a CSV row starting with this number */
    private final static int ROUND_INFO_TYPE = 1;

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

            RoundInfoPrev roundInfoPrev = HashgraphInfo.FIRST_ROUND_INFO_PREV;
            printRoundInfoPrev(out, roundInfoPrev);

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
            for (StackTraceElement line : e.getStackTrace()) {
                System.out.println(line.toString());
            }


            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw); // Redirects the stack trace stream

            String fullStackTrace = sw.toString();
            System.out.println(fullStackTrace);
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

    /** return the eventID of an event, or -1 if null */
    private static long eventID(EventInfo event) {
        return event == null ? -1 : event.getEventID();
    }

    /** append to the CSV line an array of events (length then each eventID) with comma before but not after */
    private static void appendEvents(StringBuilder line, EventInfo[] events) {
        line.append(",").append(events.length);
        for (EventInfo event : events) {
            if (event != null) {
                line.append(",").append(eventID(event));
            }
        }
    }

    /** append to the CSV line an array of longs (length then each long) with comma before but not after */
    private static void appendLongs(StringBuilder line, long[] longs) {
        line.append(",").append(longs.length);
        for (Long n : longs) {
            line.append(",").append(n);
        }
    }

    /** append to the CSV line an array of booleans (length then each as 0=false 1=true) with comma before not after */
    private static void appendBooleans(StringBuilder line, boolean[] booleans) {
        line.append(",").append(booleans.length);
        for (boolean b : booleans) {
            line.append(",").append(b ? 1 : 0);
        }
    }

    /** return one line of the CSV file describing the RoundInfoPrev fields */
    private static void printRoundInfoPrev(PrintWriter out, RoundInfoPrev roundInfoPrev) {
        StringBuilder line = new StringBuilder();
        line.append(ROUND_INFO_PREV_TYPE);
        line.append(",").append(roundInfoPrev.pendingRound());
        line.append(",").append(roundInfoPrev.prevJudgeCon1() ? 1 : 0);
        appendEvents(line,roundInfoPrev.prevJudges());
        line.append(",").append(roundInfoPrev.prevJudgesCopied() ? 1 : 0);
        line.append(",").append(roundInfoPrev.prevMinNonAncientRound());
        line.append(",").append(roundInfoPrev.prevNumCons());
        line.append(",").append(roundInfoPrev.prevMinJudgeBirthRound());
        out.println(line);
    }

    /** return one line of the CSV file describing the RoundInfo fields */
    private static void printRoundInfo(PrintWriter out, RoundInfo roundInfo) {
        StringBuilder line = new StringBuilder();
        line.append(ROUND_INFO_TYPE);
        line.append(",").append(roundInfo.pendingRound());
        appendLongs(line, roundInfo.nodes());
        appendLongs(line, roundInfo.stake());
        line.append(",").append(roundInfo.seeNum());
        line.append(",").append(roundInfo.seeDen());
        line.append(",").append(roundInfo.judgeCon1() ? 1 : 0);
        line.append(",").append(roundInfo.coinInterval());
        line.append(",").append(roundInfo.targetNumRoundsNonAncient());
        line.append(",").append(roundInfo.numRoundsAddressBook());
        out.println(line);
    }

    /** return one line of the CSV file describing the EventInfo fields in the signed events, before updating */
    private static void printEventSigned(PrintWriter out, EventInfo eventInfo) {
        StringBuilder line = new StringBuilder();
        line.append(EVENT_SIGNED_TYPE);
        line.append(",").append(eventID(eventInfo));
        // payload skipped because it doesn't affect consensus and has no simple way to put in a CSV
        line.append(",").append(eventInfo.getTimeCreated().getEpochSecond());
        line.append(",").append(eventInfo.getTimeCreated().getNano());
        line.append(",").append(eventInfo.getCreator());
        line.append(",").append(eventInfo.getBirthRound());
        line.append(",").append(eventInfo.getCoin());
        appendEvents(line,eventInfo.getParentsSigned());
        // parentBirthRounds, parentCreators, signature skipped because they don't affect consensus
        out.println(line);
    }

    /**
     * Return one line of the CSV file describing the given EventInfo with calculated memoized fields.
     * This must be called immediately after eventInfo.update(...) because some fields are read from the hashgraph.
     */
    private static void printEventInfo(PrintWriter out, EventInfo eventInfo) {
        StringBuilder line = new StringBuilder();
        HashgraphInfo h = eventInfo.getHashgraph();;
        line.append(EVENT_INFO_TYPE);
        line.append(",").append(eventID(eventInfo));
        line.append(",").append(eventID(eventInfo.getSelfParent()));
        line.append(",").append(eventInfo.getMaxJudgeRound());
        appendEvents(line, h.getParents().toArray(EventInfo[]::new));
        line.append(",").append(h.getTotalStake());
        line.append(",").append(h.getMinNonAncientRound());
        line.append(",").append(h.getVoteD());
        appendBooleans(line, eventInfo.getAncestorJudge());
        line.append(",").append(eventInfo.getGen());
        appendEvents(line, eventInfo.getLastSee());
        appendEvents(line, eventInfo.getStronglySeeP());
        line.append(",").append(eventInfo.getVotingRound());
        line.append(",").append(eventID(eventInfo.getFirstSelfWitnessS()));
        line.append(",").append(eventID(eventInfo.getFirstWitnessS()));
        appendEvents(line, eventInfo.getStronglySeeS1());
        appendEvents(line, eventInfo.getVoteE());
        appendBooleans(line, eventInfo.getVoteB());
        line.append(",").append(eventInfo.isConsensus() ? 1 : 0);
        line.append(",").append(eventInfo.getConsensusOrder());
        Instant t = eventInfo.getConsensusTimestamp();
        line.append(",").append(t==null ? -1 : t.getEpochSecond());
        line.append(",").append(t==null ? -1 : t.getNano());
        out.println(line);
    }

    /** return one line of the CSV file describing the given EventInfo with calculated memoized fields */
    private static void printUpdateResults(PrintWriter out, UpdateResults updateResults) {
        StringBuilder line = new StringBuilder();
        line.append(UPDATE_RESULTS_TYPE);
        appendEvents(line,updateResults.consensusEvents());
        line.append(",").append(updateResults.roundTimestamp().getEpochSecond());
        line.append(",").append(updateResults.roundTimestamp().getNano());
        line.append(",").append(updateResults.voteD());
        line.append(",").append(updateResults.usedCoin() ? 1 : 0);
        // skip roundInfoPrev because it is a line of its own
        out.println(line);
    }
}
