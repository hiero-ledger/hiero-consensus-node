package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.GrpcUsageTrackerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GrpcUsageTracker implements ServerInterceptor {

    /**
     * Logger used to write GRPC access information to a unique log file.
     */
    private static final Logger accessLogger = LogManager.getLogger("grpc-access-log");

    /**
     * Key used to extract the {@code X-User-Agent} header from the GRPC metadata.
     */
    private static final Key<String> userAgentHeaderKey = Key.of("X-User-Agent", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Cache used to hold mappings of raw user-agent strings to parsed/sanitized user-agent parts.
     * <p>For example, a mapping may be:
     * {@code "hiero-sdk-java/2.1.3 foo-bar/16 baz" -> UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.1.3")}
     */
    private final Cache<String, UserAgent> userAgentCache;

    private final AtomicReference<UsageBucket> bucketRef;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    private final ConfigProvider configProvider;

    public GrpcUsageTracker(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);

        final GrpcUsageTrackerConfig config = configProvider.getConfiguration()
                .getConfigData(GrpcUsageTrackerConfig.class);

        userAgentCache = Caffeine.newBuilder()
                .maximumSize(config.userAgentCacheSize())
                .build();
        isEnabled.set(config.enabled());
        executor.schedule(this::logAndResetUsageData, config.dumpIntervalMinutes(), TimeUnit.MINUTES);

        final Instant bucketTime = calculateCurrentBucketTime();
        bucketRef = new AtomicReference<>(new UsageBucket(bucketTime));
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call, final Metadata headers,
            final ServerCallHandler<ReqT, RespT> next) {

        final MethodDescriptor<?, ?> descriptor = call.getMethodDescriptor();
        final String userAgentStr = headers.get(userAgentHeaderKey);

        final RpcName rpcName = RpcNames.from(descriptor);
        final UserAgent userAgent = userAgentStr == null || userAgentStr.isBlank()
                ? UserAgent.UNSPECIFIED
                : userAgentCache.get(userAgentStr, UserAgent::from);

        bucketRef.get().recordInteraction(rpcName, userAgent);

        return next.startCall(call, headers);
    }

    private void logAndResetUsageData() {
        final UsageBucket usageBucket = bucketRef.getAndSet(new UsageBucket(calculateCurrentBucketTime()));
        final String time = usageBucket.time.toString();

        usageBucket.usageData.forEach((rpcName, usagesByAgent) -> {
            final String endpoint = rpcName.serviceName() + ":" + rpcName.methodName();
            usagesByAgent.forEach((userAgent, counter) -> {
                accessLogger.info("|time={}|endpoint={}|sdkType={}|sdkVersion={}|count={}|", time, endpoint,
                        userAgent.agentType().id(), userAgent.version(), counter.sum());
            });
        });
    }

    private Instant calculateCurrentBucketTime() {
        final int bucketIntervalMinutes = configProvider.getConfiguration()
                .getConfigData(GrpcUsageTrackerConfig.class)
                .dumpIntervalMinutes();
        final Instant now = Instant.now();
        final int minutes = now.get(ChronoField.MINUTE_OF_HOUR);
        final int rem = minutes % bucketIntervalMinutes;

        return now.truncatedTo(ChronoUnit.MINUTES)
                .minus(rem, ChronoUnit.MINUTES);
    }

    private record UsageBucket(@NonNull Instant time,
                               @NonNull ConcurrentMap<RpcName, ConcurrentMap<UserAgent, LongAdder>> usageData) {

        UsageBucket {
            requireNonNull(time, "time is required");
            requireNonNull(usageData, "usageData is required");
        }

        UsageBucket(@NonNull final Instant time) {
            this(time, new ConcurrentHashMap<>(100));
        }

        void recordInteraction(@NonNull final RpcName rpcName, @NonNull final UserAgent userAgent) {
            requireNonNull(rpcName, "rpcName is required");
            requireNonNull(userAgent, "userAgent is required");

            usageData.computeIfAbsent(rpcName, __ -> new ConcurrentHashMap<>())
                    .computeIfAbsent(userAgent, __ -> new LongAdder())
                    .increment();
        }
    }
}
