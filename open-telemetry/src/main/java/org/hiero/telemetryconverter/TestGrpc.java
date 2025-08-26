package org.hiero.telemetryconverter;

import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.GrpcClient;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.hiero.telemetryconverter.util.Utils;


public class TestGrpc {

    private record Options(Optional<String> authority, String contentType) implements ServiceInterface.RequestOptions {}

    public static final Options PROTO_OPTIONS =
            new Options(Optional.empty(), ServiceInterface.RequestOptions.APPLICATION_GRPC);

    private static final TraceServiceInterface.TraceServiceClient client = new TraceServiceInterface.TraceServiceClient(
            createGrpcClient("http://localhost:4317"), PROTO_OPTIONS);

    public static void main(String[] args) throws NoSuchAlgorithmException {
        testMultipleResourcesSameTrace();
    }

    // test two resources with same trace and some links between them
    private static void testMultipleResourcesSameTrace() throws NoSuchAlgorithmException {
        // create a digest for creating trace ids
        final MessageDigest digest = MessageDigest.getInstance("MD5");

        Resource resource1 = Resource.newBuilder()
                .attributes(new KeyValue("service.name", AnyValue.newBuilder().stringValue("r1").build()))
                .build();

        Resource resource2 = Resource.newBuilder()
                .attributes(new KeyValue("service.name", AnyValue.newBuilder().stringValue("r2").build()))
                .build();

        Instant now = Instant.now();

        final Bytes trace1 = Utils.longToHash16Bytes(digest, now.getEpochSecond());
        Bytes r1t1Id = Utils.longToHash8Bytes(now.getEpochSecond() + 1L);

        // root span of resource 1 trace 1
        Span r1t1 = Span.newBuilder()
                .traceId(trace1)
                .spanId(r1t1Id)
                .name("r1-t1-root")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(60, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(40, ChronoUnit.SECONDS)))
                .build();

        // child span of rot span of resource 1 trace 1
        Span r1t1s1 = Span.newBuilder()
                .traceId(trace1)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 11L))
                .parentSpanId(r1t1Id)
                .name("r1-t1-s1")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(55, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(50, ChronoUnit.SECONDS)))
                .build();

        // root span of resource 2 trace 1
        Bytes r2t1Id = Utils.longToHash8Bytes(now.getEpochSecond() + 2L);
        Span r2t1 = Span.newBuilder()
                .traceId(trace1)
                .spanId(r2t1Id)
                .parentSpanId(r1t1Id)
                .name("r2-t1-root")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(30, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(10, ChronoUnit.SECONDS)))
                .build();

        Span r2t1s1 = Span.newBuilder()
                .traceId(trace1)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 21L))
                .parentSpanId(r2t1Id)
                .name("r2-t1-s1")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(25, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(15, ChronoUnit.SECONDS)))
                .links(Span.Link.newBuilder().traceId(trace1).spanId(r1t1Id).build())
                .build();

        ResourceSpans r1Spans = ResourceSpans.newBuilder()
                .resource(resource1)
                .scopeSpans(ScopeSpans.newBuilder().spans(r1t1, r1t1s1).build())
                .build();
        ResourceSpans r2Spans = ResourceSpans.newBuilder()
                .resource(resource2)
                .scopeSpans(ScopeSpans.newBuilder().spans(r2t1, r2t1s1).build())
                .build();

        final ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .resourceSpans(r1Spans, r2Spans)
                .build();
        System.out.println("ExportTraceServiceRequest = " + ExportTraceServiceRequest.JSON.toJSON(request));
        client.Export(request);
    }

    // test single resource with multiple spans including nested ones
    private static void testSingleResource() throws NoSuchAlgorithmException {
        // create a digest for creating trace ids
        final MessageDigest digest = MessageDigest.getInstance("MD5");

        Resource resource = Resource.newBuilder()
                .attributes(new KeyValue("service.name", AnyValue.newBuilder().stringValue("Hedera").build()))
                .build();

        Instant now = Instant.now();
        final Bytes traceId = Utils.longToHash16Bytes(digest, now.getEpochSecond());

        Span rootSpan = Span.newBuilder()
                .traceId(traceId)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 1L))
                .name("rootSpan")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(60, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(10, ChronoUnit.SECONDS)))
                .build();

        Span span1 = Span.newBuilder()
                .traceId(traceId)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 11L))
                .parentSpanId(Utils.longToHash8Bytes(now.getEpochSecond() + 1L))
                .name("span1")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(60, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(30, ChronoUnit.SECONDS)))
                .build();

        Span span11 = Span.newBuilder()
                .traceId(traceId)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 111L))
                .parentSpanId(Utils.longToHash8Bytes(now.getEpochSecond() + 11L))
                .name("span11")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(60, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(40, ChronoUnit.SECONDS)))
                .build();

        Span span2 = Span.newBuilder()
                .traceId(traceId)
                .spanId(Utils.longToHash8Bytes(now.getEpochSecond() + 12L))
                .parentSpanId(Utils.longToHash8Bytes(now.getEpochSecond() + 1L))
                .name("span2")
                .startTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(20, ChronoUnit.SECONDS)))
                .endTimeUnixNano(Utils.instantToUnixEpocNanos(now.minus(10, ChronoUnit.SECONDS)))
                .build();

        ResourceSpans resourceSpans = ResourceSpans.newBuilder()
                .resource(resource)
                .scopeSpans(ScopeSpans.newBuilder().spans(rootSpan, span1, span2, span11).build())
                .build();
        final ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .resourceSpans(resourceSpans)
                .build();
        System.out.println("ExportTraceServiceRequest = " + ExportTraceServiceRequest.JSON.toJSON(request));
        var response = client.Export(request);
        System.out.println("response = " + response);
    }

    private static GrpcClient createGrpcClient(String baseUri) {
        final Tls tls = Tls.builder().enabled(false).build();
        final WebClient webClient = WebClient.builder().baseUri(baseUri).tls(tls).build();
        final PbjGrpcClientConfig config = new PbjGrpcClientConfig(
                Duration.ofSeconds(10), tls, Optional.empty(), ServiceInterface.RequestOptions.APPLICATION_GRPC);

        return new PbjGrpcClient(webClient, config);
    }
}
