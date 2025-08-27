package org.hiero.telemetryconverter;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static org.hiero.telemetryconverter.util.CleanColorfulFormatter.GREY;

import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.GrpcClient;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.opentelemetry.pbj.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.pbj.collector.trace.v1.TraceServiceInterface;
import io.opentelemetry.pbj.common.v1.AnyValue;
import io.opentelemetry.pbj.common.v1.KeyValue;
import io.opentelemetry.pbj.resource.v1.Resource;
import io.opentelemetry.pbj.trace.v1.ResourceSpans;
import io.opentelemetry.pbj.trace.v1.ScopeSpans;
import io.opentelemetry.pbj.trace.v1.Span;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.LogManager;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.hiero.telemetryconverter.model.VirtualResource;
import org.hiero.telemetryconverter.model.combined.BlockInfo;
import org.hiero.telemetryconverter.model.trace.BlockTraceInfo;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.spancreators.BlockSpanCreator;
import org.hiero.telemetryconverter.util.CleanColorfulFormatter;
import org.hiero.telemetryconverter.util.WarningException;

/**
 * TelemetryConverter is a utility class for converting telemetry data.
 * <p>
 * To record JFR telemetry, first start consesus node and wait for it to be ready
 * <pre>./gradlew app:run</pre>
 * Then in another terminal, run the load generator to create some trasaction load
 * <pre> java -cp network-load-generator-0.6.0.jar com.hedera.benchmark.NftTransferLoadTest -a 10 -n 10 -t 100000</pre>
 * Then in another terminal, find the PID of the node process with jps then run the jcmd command to start JFR recording
 * <pre>jcmd &lt;PID&gt; JFR.start duration=300s filename=&lt;ABSOLUTE_PATH_TO_CONSENSUS_NODE_REPO&gt;/hedera-node/hedera-app/build/node/data/trace-node-&lt;NODE_ID&gt;.jfr</pre>
 */
@SuppressWarnings("CallToPrintStackTrace")
public final class TelemetryConverter {
    private static final System.Logger LOGGER = System.getLogger(TelemetryConverter.class.getName());

    private record Options(Optional<String> authority, String contentType) implements ServiceInterface.RequestOptions {}

    public static final Options PROTO_OPTIONS =
            new Options(Optional.empty(), ServiceInterface.RequestOptions.APPLICATION_GRPC);

    private static final Path PROJECT_ROOT = Paths.get("").toAbsolutePath();
    private static final Path JFR_TRACE_DIR = PROJECT_ROOT.resolve("hedera-node/test-clients/build/hapi-test");
    // we will take blocks only from one single node
    private static final Path BLOCK_FILES_DIR = PROJECT_ROOT.resolve("hedera-node/test-clients/build/hapi-test/node0/data/blockStreams");

    /** Cache of all block traces found in the JFR file, keyed by block number. */
    private static final LongObjectHashMap<List<BlockTraceInfo>> blockTraces = new LongObjectHashMap<>();
    /** Cache of all round traces found in the JFR file, keyed by round number. */
    private static final LongObjectHashMap<List<RoundTraceInfo>> roundTraces = new LongObjectHashMap<>();
    /** Cache of all event traces found in the JFR file, keyed by event hash. */
    private static final IntObjectHashMap<List<EventTraceInfo>> eventTraces = new IntObjectHashMap<>();
    /** Cache of all transaction traces found in the JFR file, keyed by transaction hash. */
    private static final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces = new IntObjectHashMap<>();
    /** Directory to write test output files to */
    private static final Path testOutputDir = PROJECT_ROOT.resolve("build/telemetry");

    private static final TraceServiceInterface.TraceServiceClient traceClient = new TraceServiceInterface.TraceServiceClient(
            createGrpcClient("http://localhost:4317"), PROTO_OPTIONS);


    public static void main(String[] args) throws IOException {
        // load the logging configuration from the classpath and make it colorful
        try (var loggingConfigIn = TelemetryConverter.class.getClassLoader().getResourceAsStream("logging.properties")) {
            if (loggingConfigIn != null) {
                LogManager.getLogManager().readConfiguration(loggingConfigIn);
            } else {
                LOGGER.log(INFO, "No logging configuration found");
            }
        } catch (IOException e) {
            LOGGER.log(INFO, "Failed to load logging configuration", e);
        }
        CleanColorfulFormatter.makeLoggingColorful();
        LOGGER.log(INFO, "Using default logging configuration");
        // clean or create testOutputDir. Delete contents if it exists, otherwise create new directory
        try {
            if (Files.exists(testOutputDir)) {
                try (Stream<Path> childFiles = Files.walk(testOutputDir)) {
                    childFiles.filter(Files::isRegularFile)
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    System.err.printf("Error deleting file %s: %s%n", path, e.getMessage());
                                }
                            });
                }
            } else {
                Files.createDirectories(testOutputDir);
            }
        } catch (IOException e) {
            System.err.printf("Error creating build directory %s: %s%n", testOutputDir, e.getMessage());
        }
        // start with reading all trace data from JFR file into maps that we can use later
        try (Stream<Path> jfrDirFiles = Files.walk(JFR_TRACE_DIR)) {
            jfrDirFiles
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jfr"))
                    .forEach(jfrFile -> {
                        try {
                            JfrFileReader.readJfrFile(jfrFile, blockTraces, roundTraces, eventTraces, transactionTraces);
                        } catch (Exception e) {
                            System.err.printf("Error reading JFR file %s: %s%n", jfrFile, e.getMessage());
                            e.printStackTrace();
                        }
                    });
        }
        // find all nodes
        final long[] nodeIds = roundTraces.values().stream()
                .flatMap(List::stream)
                .mapToLong(RoundTraceInfo::nodeId)
                .distinct()
                .sorted()
                .toArray();
        LOGGER.log(INFO, "Found round traces from nodes: " + Arrays.toString(nodeIds));
        // now read the block files and match them with the round traces
        try(Stream<Path> paths = java.nio.file.Files.walk(BLOCK_FILES_DIR)) {
            sendSpans(paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".blk.gz"))
                    .sorted(Comparator.comparingLong(path -> {
                        // extract the block number from the file name, assuming it is in the format "<blockNum>.blk.gz"
                        String fileName = path.getFileName().toString();
                        int endIndex = fileName.indexOf('.');
                        return Long.parseLong(fileName.substring(0, endIndex));
                    }))
                    .map(blockFilePath -> {
                        try {
                            final BlockInfo blockInfo = new BlockInfo(blockFilePath,
                                    blockTraces, roundTraces, eventTraces, transactionTraces);
                            LOGGER.log(INFO, "Created: " + blockInfo);
                            if (blockInfo.blockCreationTraces().isEmpty()) {
                                LOGGER.log(WARNING, "No block creation traces found for block " +
                                        blockInfo.blockNum() + " so skipping creating traces");
                                return null;
                            }
                            return blockInfo;
                        } catch (WarningException e) {
                            LOGGER.log(WARNING, e.getMessage());
                        } catch (Exception e) {
                            LOGGER.log(ERROR, "Error creating BlockInfo for block " + blockFilePath.getFileName(), e);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .map(blockInfo -> BlockSpanCreator.createBlockSpans(blockInfo, nodeIds))
//                    .limit(20) // TODO limit to first 5 blocks for testing
                    // merge all the maps into one map
                    .flatMap(map -> map.entrySet().stream())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (list1, list2) -> {
                                List<Span> mergedList = new ArrayList<>(list1);
                                mergedList.addAll(list2);
                                return mergedList;
                            }
                    )));
        }
    }

    private static final AtomicLong spanIdCounter = new AtomicLong(0L);

    private static void sendSpans(final Map<VirtualResource,List<Span>> spanMap) {
        final int numOfSpans = spanMap.values().stream().mapToInt(List::size).sum();
        LOGGER.log(INFO, "Start sending "+numOfSpans+" spans to OpenTelemetry collector");
        try {
            final ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                    .resourceSpans(
                            spanMap.entrySet().stream().map(entry -> {
                                final VirtualResource vr = entry.getKey();
                                final List<Span> spans = entry.getValue();
                                return ResourceSpans.newBuilder()
                                        .resource(vr.resource)
                                        .scopeSpans(ScopeSpans.newBuilder().spans(spans).build())
                                        .build();
                            }).toList())
                    .build();
            // write json file
            try(WritableStreamingData out = new WritableStreamingData(Files.newOutputStream(
                    testOutputDir.resolve("ExportTraceServiceRequests"+spanIdCounter.incrementAndGet()+".json")))) {
                ExportTraceServiceRequest.JSON.write(request, out);
            } catch (IOException e) {
                System.err.printf("Error writing spans to file: %s%n", e.getMessage());
            }
            // send to GRPC
            var response = traceClient.Export(request);
            LOGGER.log(INFO, "Sent "+numOfSpans+" spans to OpenTelemetry collector :: "+ GREY+ response);
        } catch (Exception e) {
            LOGGER.log(ERROR, "Error sending spans to OpenTelemetry collector: " + e.getMessage(), e);
        }
    }

    private static GrpcClient createGrpcClient(String baseUri) {
        final Tls tls = Tls.builder().enabled(false).build();
        final WebClient webClient = WebClient.builder().baseUri(baseUri).tls(tls).build();
        final PbjGrpcClientConfig config = new PbjGrpcClientConfig(
                Duration.ofSeconds(10), tls, Optional.empty(), ServiceInterface.RequestOptions.APPLICATION_GRPC);

        return new PbjGrpcClient(webClient, config);
    }

}