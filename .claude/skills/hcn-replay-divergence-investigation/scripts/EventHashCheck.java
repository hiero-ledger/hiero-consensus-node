// SPDX-License-Identifier: Apache-2.0
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.statevalidation.blockstream.BlockStreamEventBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Investigation tool: rebuilds events from block files with the validator's own {@link BlockStreamEventBuilder} and
 * counts parent references whose hash matches no rebuilt event. In a correct reconstruction this is ~0 after a short
 * warm-up (parents older than the first block are unknown). A large count means rebuilt event hashes differ from the
 * hashes children reference, which blocks-to-pces reports as "stale parents" and which breaks replay consensus.
 *
 * <p>Requires a validator jar whose BlockStreamEventBuilder has the (long eventCutoverMinBirthRound) constructor
 * (0.79+).
 *
 * <pre>
 * javac -proc:none -cp &lt;validator.jar&gt; -d out EventHashCheck.java
 * java -cp out:&lt;validator.jar&gt; EventHashCheck &lt;blocks-dir&gt; &lt;max-blocks&gt; &lt;warmup-blocks&gt; [cutover-min-birth-round]
 *   (Windows: use ';' as the classpath separator and C:/... paths)
 * cutover-min-birth-round: 0 = all events SHA-256 (as logged by blocks-to-pces), omit = no cutover (SHA-384)
 * </pre>
 *
 * <p>To test a fix, compile the patched class against the jar into another directory and put it first on the
 * classpath: {@code java -cp patched:out:<jar> EventHashCheck ...}.
 */
public class EventHashCheck {
    public static void main(final String[] args) throws Exception {
        final Path dir = Paths.get(args[0]);
        final int maxBlocks = Integer.parseInt(args[1]);
        final int warmup = Integer.parseInt(args[2]);
        final long cutover = args.length > 3 ? Long.parseLong(args[3]) : Long.MAX_VALUE;

        final List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".blk.gz")).sorted().toList();
        }
        // Keep only the leading run of consecutive block numbers.
        final List<Path> run = new ArrayList<>();
        long prev = -1;
        for (final Path p : files) {
            final long n = Long.parseLong(p.getFileName().toString().replace(".blk.gz", ""));
            if (prev >= 0 && n != prev + 1) {
                break;
            }
            run.add(p);
            prev = n;
            if (run.size() == maxBlocks) {
                break;
            }
        }
        if (run.isEmpty()) {
            throw new IllegalArgumentException("No .blk.gz files in " + dir);
        }

        EventHashFactory.initialize(cutover);
        final BlockStreamEventBuilder builder = new BlockStreamEventBuilder(cutover);
        final Set<String> produced = new HashSet<>();
        final long[] c = new long[5]; // events, eventsWithTx, parentRefs, unmatched, unmatchedAfterWarmup
        for (int i = 0; i < run.size(); i++) {
            final boolean counted = i >= warmup;
            builder.processBlock(BlockStreamAccess.blockFrom(run.get(i)), (final PlatformEvent e) -> {
                c[0]++;
                if (!e.getGossipEvent().transactions().isEmpty()) {
                    c[1]++;
                }
                for (final EventDescriptorWrapper parent : e.getAllParents()) {
                    c[2]++;
                    if (!produced.contains(parent.hash().toHex())) {
                        c[3]++;
                        if (counted) {
                            c[4]++;
                        }
                    }
                }
                produced.add(e.getHash().toHex());
            });
        }
        System.out.printf(
                "blocks=%d (%s..%s) events=%d eventsWithTx=%d parentRefs=%d unmatched=%d unmatchedAfterWarmup(%d)=%d%n",
                run.size(),
                run.getFirst().getFileName(),
                run.getLast().getFileName(),
                c[0],
                c[1],
                c[2],
                c[3],
                warmup,
                c[4]);
    }
}
