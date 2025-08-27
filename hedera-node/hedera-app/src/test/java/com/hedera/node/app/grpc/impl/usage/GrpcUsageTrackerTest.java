// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
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
import java.util.Map.Entry;
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
    static final VarHandle userAgentCacheHandle;

    static {
        try {
            usageBucketRefHandle = MethodHandles.privateLookupIn(GrpcUsageTracker.class, MethodHandles.lookup())
                    .findVarHandle(GrpcUsageTracker.class, "bucketRef", AtomicReference.class);
            userAgentCacheHandle = MethodHandles.privateLookupIn(GrpcUsageTracker.class, MethodHandles.lookup())
                    .findVarHandle(GrpcUsageTracker.class, "userAgentCache", Cache.class);
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

        assertThatCode(() -> usageTracker.interceptCall(serverCall, metadata, handler))
                .doesNotThrowAnyException();

        // get the usage bucket... there should be no data captured in it since usage tracking is disabled
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        final UsageBucket usageBucket = bucketRef.get();
        assertThat(usageBucket.usageData()).isEmpty();
        assertThat(usageBucket.time()).isEqualTo("2025-04-03T15:30:00.000Z");
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
        assertThatCode(() -> usageTracker.interceptCall(serverCall, metadata, handler))
                .doesNotThrowAnyException();
        assertThatCode(() -> usageTracker.interceptCall(serverCall, metadata, handler))
                .doesNotThrowAnyException();

        // get the usage bucket... there should be some usage data captured
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        final UsageBucket usageBucket = bucketRef.get();

        final ConcurrentMap<RpcEndpointName, ConcurrentMap<UserAgent, LongAdder>> usageData = usageBucket.usageData();
        assertThat(usageData).hasSize(1);
        final ConcurrentMap<UserAgent, LongAdder> agentData = usageData.get(new RpcEndpointName("MyService", "Commit"));
        assertThat(agentData).hasSize(1);
        final LongAdder counter = agentData.get(new UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.3.1"));
        assertThat(counter.sum()).isEqualTo(2);
        assertThat(usageBucket.time()).isEqualTo("2025-04-03T15:30:00.000Z");
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

        // MyService:Commit
        assertThatCode(() -> usageTracker.interceptCall(serverCall, javaMetadata, handler))
                .doesNotThrowAnyException();
        // MyService:Get
        assertThatCode(() -> usageTracker.interceptCall(serverCall, goMetadata, handler))
                .doesNotThrowAnyException();
        // MyService:Commit
        assertThatCode(() -> usageTracker.interceptCall(serverCall, luaMetadata, handler))
                .doesNotThrowAnyException();
        // MyService:Commit
        assertThatCode(() -> usageTracker.interceptCall(serverCall, javaMetadata, handler))
                .doesNotThrowAnyException();

        // log the usage data out
        assertThatCode(usageTracker::logAndResetUsageData).doesNotThrowAnyException();

        final List<String> logs = accessLogCaptor.infoLogs();
        assertThat(logs).hasSize(3);
        assertThat(logs)
                .contains(
                        "|time=2025-04-03T15:30:00Z|service=MyService|method=Commit|sdkType=HieroSdkJava|sdkVersion=2.3.1|count=2|",
                        "|time=2025-04-03T15:30:00Z|service=MyService|method=Commit|sdkType=Unknown|sdkVersion=Unknown|count=1|",
                        "|time=2025-04-03T15:30:00Z|service=MyService|method=Get|sdkType=HieroSdkGo|sdkVersion=1.5.6|count=1|");

        // validate that the bucket has been reset
        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        final UsageBucket usageBucket = bucketRef.get();
        assertThat(usageBucket.usageData()).isEmpty();
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

        final Instant actualTime = usageTracker.toBucketTime(time);
        assertThat(actualTime).isEqualTo(expectedTime);
    }

    static List<Arguments> testTimeCalculationArgs() {
        return List.of(
                Arguments.of(Instant.parse("2025-04-03T15:32:32.426457Z"), Instant.parse("2025-04-03T15:30:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:00:30.426457Z"), Instant.parse("2025-04-03T15:00:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:05:15.426457Z"), Instant.parse("2025-04-03T15:00:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:15:45.000Z"), Instant.parse("2025-04-03T15:15:00.000Z")),
                Arguments.of(Instant.parse("2025-04-03T15:55:23.426457Z"), Instant.parse("2025-04-03T15:45:00.000Z")));
    }

    @Test
    void testLargeUserAgent() {
        // max user-agent string length supported is 250 characters, anything beyond this is ignored
        final String userAgent = "hiero-sdk-java/2.3.1";
        final String filler = "x".repeat(250);
        final String userAgentString = userAgent + " " + filler + " " + userAgent;

        // in a "normal" case, since there are two valid user-agent fragments it should resolve to unknown
        // however, because the second valid fragment is beyond the 250 character cutoff, it will not be seen

        final Clock clock = Clock.fixed(Instant.parse("2025-04-03T15:32:32.426457Z"), ZoneOffset.UTC);
        final GrpcUsageTrackerConfig config = new GrpcUsageTrackerConfig(true, 15, 100);
        final ConfigProvider configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        final Metadata metadata = new Metadata();
        metadata.put(userAgentHeaderKey, userAgentString);
        final MethodDescriptor<String, String> descriptor = newDescriptor("proto.MyService/commit");

        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(GrpcUsageTrackerConfig.class)).thenReturn(config);
        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);

        final GrpcUsageTracker usageTracker = new GrpcUsageTracker(configProvider, clock);

        assertThatCode(() -> usageTracker.interceptCall(serverCall, metadata, handler))
                .doesNotThrowAnyException();

        final AtomicReference<UsageBucket> bucketRef =
                (AtomicReference<UsageBucket>) usageBucketRefHandle.get(usageTracker);
        final UsageBucket usageBucket = bucketRef.get();

        final ConcurrentMap<RpcEndpointName, ConcurrentMap<UserAgent, LongAdder>> usageData = usageBucket.usageData();
        assertThat(usageData).hasSize(1);
        final ConcurrentMap<UserAgent, LongAdder> agentData = usageData.get(new RpcEndpointName("MyService", "Commit"));
        assertThat(agentData).hasSize(1);
        final LongAdder counter = agentData.get(new UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.3.1"));
        assertThat(counter.sum()).isEqualTo(1);
        assertThat(usageBucket.time()).isEqualTo("2025-04-03T15:30:00.000Z");

        // make sure the cached value is the trimmed variant and not the full user-agent string
        final String expectedUaString = userAgentString.substring(0, 250);
        final Cache<String, UserAgent> cache = (Cache<String, UserAgent>) userAgentCacheHandle.get(usageTracker);
        final ConcurrentMap<String, UserAgent> map = cache.asMap();
        assertThat(map).hasSize(1);
        final Entry<String, UserAgent> entry = map.entrySet().iterator().next();
        assertThat(entry.getKey()).hasSize(250);
        assertThat(entry.getKey()).isEqualTo(expectedUaString);
        assertThat(entry.getValue()).isEqualTo(new UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.3.1"));
    }

    static MethodDescriptor<String, String> newDescriptor(final String fullMethodName) {
        final Marshaller<String> marshaller = mock(Marshaller.class);
        return MethodDescriptor.newBuilder(marshaller, marshaller)
                .setType(MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .build();
    }
}
