// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.grpc.impl.usage.GrpcUsageTracker.UsageBucket;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.GrpcUsageTrackerConfig;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GrpcUsageTrackerTest {

    private static final Key<String> userAgentHeaderKey = Key.of("X-User-Agent", Metadata.ASCII_STRING_MARSHALLER);

    static LogCaptor accessLogCaptor = new LogCaptor(LogManager.getLogger("grpc-access-log"));

    static final VarHandle usageBucketRefHandle;

    static {
        try {
            usageBucketRefHandle = MethodHandles.privateLookupIn(GrpcUsageTracker.class, MethodHandles.lookup())
                    .findVarHandle(GrpcUsageTracker.class, "bucketRef", AtomicReference.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void afterEach() {
        accessLogCaptor.stopCapture();
        accessLogCaptor = new LogCaptor(LogManager.getLogger("grpc-access-log"));
    }

    @AfterAll
    static void afterAll() {
        accessLogCaptor.stopCapture();
    }

    @Test
    void testInterceptDisabled() {
        final Clock clock = Clock.fixed(Instant.parse("2025-04-03T15:32:32.426457Z"), ZoneOffset.UTC);
        final GrpcUsageTrackerConfig config = new GrpcUsageTrackerConfig(false, 15, 100);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        final Metadata metadata = new Metadata();
        metadata.put(userAgentHeaderKey, "hiero-sdk-java/2.3.1");
        final MethodDescriptor<String, String> descriptor = newDescriptor("proto.MyService/commit");

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(GrpcUsageTrackerConfig.class)).thenReturn(config);
        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);

        final GrpcUsageTracker usageTracker = new GrpcUsageTracker(configProvider, clock);

        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, metadata, handler));

        // get the usage bucket... there should be no data captured in it since usage tracking is disabled
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        assertNotNull(bucketRef);
        final UsageBucket usageBucket = bucketRef.get();
        assertNotNull(usageBucket);
        assertTrue(usageBucket.usageData().isEmpty());
        assertEquals(usageBucket.time(), Instant.parse("2025-04-03T15:30:00.000Z"));
    }

    @Test
    void testInterceptEnabled() {
        final Clock clock = Clock.fixed(Instant.parse("2025-04-03T15:32:32.426457Z"), ZoneOffset.UTC);
        final GrpcUsageTrackerConfig config = new GrpcUsageTrackerConfig(true, 15, 100);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        final Metadata metadata = new Metadata();
        metadata.put(userAgentHeaderKey, "hiero-sdk-java/2.3.1");
        final MethodDescriptor<String, String> descriptor = newDescriptor("proto.MyService/commit");

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(GrpcUsageTrackerConfig.class)).thenReturn(config);
        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);

        final GrpcUsageTracker usageTracker = new GrpcUsageTracker(configProvider, clock);

        // make two calls
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, metadata, handler));
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, metadata, handler));

        // get the usage bucket... there should be no data captured in it since usage tracking is disabled
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        assertNotNull(bucketRef);
        final UsageBucket usageBucket = bucketRef.get();
        assertNotNull(usageBucket);

        final ConcurrentMap<RpcEndpointName, ConcurrentMap<UserAgent, LongAdder>> usageData = usageBucket.usageData();
        assertEquals(1, usageData.size());
        final ConcurrentMap<UserAgent, LongAdder> agentData = usageData.get(new RpcEndpointName("MyService", "Commit"));
        assertNotNull(agentData);
        assertEquals(1, agentData.size());
        final LongAdder counter = agentData.get(new UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.3.1"));
        assertNotNull(counter);
        assertEquals(2, counter.sum());

        assertEquals(usageBucket.time(), Instant.parse("2025-04-03T15:30:00.000Z"));
    }

    @Test
    void testLogOutput() {
        final Clock clock = Clock.fixed(Instant.parse("2025-04-03T15:32:32.426457Z"), ZoneOffset.UTC);
        final GrpcUsageTrackerConfig config = new GrpcUsageTrackerConfig(true, 15, 100);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        final Metadata javaMetadata = new Metadata();
        javaMetadata.put(userAgentHeaderKey, "hiero-sdk-java/2.3.1");
        final Metadata goMetadata = new Metadata();
        goMetadata.put(userAgentHeaderKey, "hiero-sdk-go/1.5.6");
        final Metadata luaMetadata = new Metadata();
        luaMetadata.put(userAgentHeaderKey, "hiero-sdk-lua/0.0.1");

        final MethodDescriptor<String, String> commitDescriptor = newDescriptor("proto.MyService/commit");
        final MethodDescriptor<String, String> getDescriptor = newDescriptor("proto.MyService/get");

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(GrpcUsageTrackerConfig.class)).thenReturn(config);
        when(serverCall.getMethodDescriptor())
                .thenReturn(commitDescriptor, getDescriptor, commitDescriptor, commitDescriptor);

        final GrpcUsageTracker usageTracker = new GrpcUsageTracker(configProvider, clock);

        // make two calls
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, javaMetadata, handler)); // MyService:Commit
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, goMetadata, handler)); // MyService:Get
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, luaMetadata, handler)); // MyService:Commit
        assertDoesNotThrow(() -> usageTracker.interceptCall(serverCall, javaMetadata, handler)); // MyService:Commit

        assertDoesNotThrow(usageTracker::logAndResetUsageData); // log the usage data out

        List<String> logs = accessLogCaptor.infoLogs();
        assertEquals(3, logs.size());
        final String log1 =
                "|time=2025-04-03T15:30:00Z|service=MyService|method=Commit|sdkType=HieroSdkJava|sdkVersion=2.3.1|count=2|";
        final String log2 =
                "|time=2025-04-03T15:30:00Z|service=MyService|method=Commit|sdkType=Unknown|sdkVersion=Unknown|count=1|";
        final String log3 =
                "|time=2025-04-03T15:30:00Z|service=MyService|method=Get|sdkType=HieroSdkGo|sdkVersion=1.5.6|count=1|";

        assertTrue(logs.contains(log1));
        assertTrue(logs.contains(log2));
        assertTrue(logs.contains(log3));

        // validate that the bucket has been reset
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        assertNotNull(bucketRef);
        final UsageBucket usageBucket = bucketRef.get();
        assertNotNull(usageBucket);
        assertTrue(usageBucket.usageData().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("testTimeCalculationArgs")
    void testTimeCalculation(final Instant time, final Instant expectedTime) {
        final GrpcUsageTrackerConfig config = new GrpcUsageTrackerConfig(false, 15, 100);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(GrpcUsageTrackerConfig.class)).thenReturn(config);

        final GrpcUsageTracker usageTracker = new GrpcUsageTracker(configProvider);

        final Instant actualTime = assertDoesNotThrow(() -> usageTracker.toBucketTime(time));
        assertEquals(expectedTime, actualTime);
    }

    static List<Arguments> testTimeCalculationArgs() {
        return List.of(
                Arguments.of(Instant.parse("2025-04-03T15:32:32.426457Z"), Instant.parse("2025-04-03T15:30:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:00:30.426457Z"), Instant.parse("2025-04-03T15:00:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:05:15.426457Z"), Instant.parse("2025-04-03T15:00:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:15:45.000Z"), Instant.parse("2025-04-03T15:15:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:55:23.426457Z"), Instant.parse("2025-04-03T15:45:00.000Z")));
    }

    static MethodDescriptor<String, String> newDescriptor(final String fullMethodName) {
        final Marshaller<String> marshaller = mock(Marshaller.class);
        return MethodDescriptor.newBuilder(marshaller, marshaller)
                .setType(MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .build();
    }
}
