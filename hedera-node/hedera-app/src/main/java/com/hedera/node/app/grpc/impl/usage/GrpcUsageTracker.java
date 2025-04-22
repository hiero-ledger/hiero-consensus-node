// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

/**
 * Interceptor that captures gRPC usage data based on the invoked RPC endpoint and user-agent information from the
 * {@code X-User-Agent} header. This works by aggregating gRPC usage data into a time bucket which is a period of time
 * based on the {@link GrpcUsageTrackerConfig#logIntervalMinutes() logging interval}. For example, if the logging
 * interval is 15 minutes, then the bucket will contain 15 minutes worth of usage data.
 */
public class GrpcUsageTracker implements ServerInterceptor {

    /**
     * The maximum length used for user-agent parsing and caching. If a user-agent exceeds this length, only the
     * beginning up to the max will be used.
     */
    private static final int MAX_UA_LENGTH = 250;

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

    /**
     * Reference to the active bucket that contains usage data.
     */
    private final AtomicReference<UsageBucket> bucketRef;

    /**
     * Executor used for periodic config refresh and dumping usage data to the log.
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /**
     * Flag used to indicate if usage tracking is enabled or not. This is refreshed at every
     * {@link GrpcUsageTrackerConfig#logIntervalMinutes() logging interval} to permit dynamic enabling/disabling.
     */
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);

    /**
     * Configuration mechanism to dynamically load configuration.
     */
    private final ConfigProvider configProvider;

    /**
     * Clock used to get the current time. (mainly exists to aid in testing)
     */
    private final Clock clock;

    /**
     * Create a new instance of the usage tracker.
     *
     * @param configProvider the configuration provider
     * @see GrpcUsageTracker#GrpcUsageTracker(ConfigProvider, Clock)
     */
    public GrpcUsageTracker(@NonNull final ConfigProvider configProvider) {
        this(configProvider, Clock.systemUTC());
    }

    /**
     * Create a new instance of the usage tracker.
     *
     * @param configProvider the configuration provider
     * @param clock the clock to use for getting timestamps of usage data
     */
    @VisibleForTesting
    GrpcUsageTracker(@NonNull final ConfigProvider configProvider, @NonNull final Clock clock) {
        this.configProvider = requireNonNull(configProvider);
        this.clock = requireNonNull(clock);

        final GrpcUsageTrackerConfig config =
                configProvider.getConfiguration().getConfigData(GrpcUsageTrackerConfig.class);

        userAgentCache =
                Caffeine.newBuilder().maximumSize(config.userAgentCacheSize()).build();

        scheduleNext();

        final Instant bucketTime = toBucketTime(clock.instant());
        bucketRef = new AtomicReference<>(new UsageBucket(bucketTime));
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
            final ServerCall<ReqT, RespT> call, final Metadata headers, final ServerCallHandler<ReqT, RespT> next) {

        if (isEnabled.get()) {
            final MethodDescriptor<?, ?> descriptor = call.getMethodDescriptor();
            final String userAgentString = headers.get(userAgentHeaderKey);
            final String uaString;

            if (userAgentString != null && userAgentString.length() > MAX_UA_LENGTH) {
                uaString = userAgentString.substring(0, MAX_UA_LENGTH);
            } else {
                uaString = userAgentString;
            }

            final RpcEndpointName rpcEndpointName = RpcEndpointName.from(descriptor);
            final UserAgent userAgent = uaString == null || uaString.isBlank()
                    ? UserAgent.UNSPECIFIED
                    : userAgentCache.get(uaString, UserAgent::from);

            bucketRef.get().recordInteraction(rpcEndpointName, userAgent);
        }

        return next.startCall(call, headers);
    }

    /**
     * Logs the most recent round of usage data collected and schedules the next iteration.
     */
    @VisibleForTesting
    void logAndResetUsageData() {
        scheduleNext();

        final Instant nextTime = toBucketTime(clock.instant());
        final UsageBucket usageBucket = bucketRef.getAndSet(new UsageBucket(nextTime));
        final String time = usageBucket.time.toString();

        usageBucket.usageData.forEach((rpcEndpointName, usagesByAgent) -> {
            usagesByAgent.forEach((userAgent, counter) -> {
                accessLogger.info(
                        "|time={}|service={}|method={}|sdkType={}|sdkVersion={}|count={}|",
                        time,
                        rpcEndpointName.serviceName(),
                        rpcEndpointName.methodName(),
                        userAgent.agentType().id(),
                        userAgent.version(),
                        counter.sum());
            });
        });
    }

    /**
     * Schedules the next iteration. The configuration is re-read to pick up any changes to whether or not usage
     * tracking is enabled and if the interval between usage logging changes.
     */
    private void scheduleNext() {
        final GrpcUsageTrackerConfig config =
                configProvider.getConfiguration().getConfigData(GrpcUsageTrackerConfig.class);

        isEnabled.set(config.enabled());
        executor.schedule(this::logAndResetUsageData, config.logIntervalMinutes(), TimeUnit.MINUTES);
    }

    /**
     * Calculates the "bucket" time for the specified time. The bucket time is based on the interval specified in the
     * configuration. For example, if the interval is 15 minutes the bucket time will be the specified time rounded down
     * to the nearest 15-minute block such as :15 or :30.
     *
     * @param time the time to base the bucket time
     * @return a new Instant whose time reflects the bucket timestamp based on the specified time and configuration
     */
    @VisibleForTesting
    @NonNull
    Instant toBucketTime(final @NonNull Instant time) {
        final int bucketIntervalMinutes = configProvider
                .getConfiguration()
                .getConfigData(GrpcUsageTrackerConfig.class)
                .logIntervalMinutes();

        final ZonedDateTime zdt = time.atZone(ZoneOffset.UTC);
        final int minutes = zdt.getMinute();
        final int rem = minutes % bucketIntervalMinutes;

        return time.truncatedTo(ChronoUnit.MINUTES).minus(rem, ChronoUnit.MINUTES);
    }

    /**
     * A "bucket" used to hold captured usage data for a given period of time.
     *
     * @param time the starting time for data captured
     * @param usageData the captured usage data
     */
    @VisibleForTesting
    record UsageBucket(
            @NonNull Instant time,
            @NonNull ConcurrentMap<RpcEndpointName, ConcurrentMap<UserAgent, LongAdder>> usageData) {

        UsageBucket {
            requireNonNull(time, "time is required");
            requireNonNull(usageData, "usageData is required");
        }

        UsageBucket(@NonNull final Instant time) {
            this(time, new ConcurrentHashMap<>(100));
        }

        void recordInteraction(@NonNull final RpcEndpointName rpcEndpointName, @NonNull final UserAgent userAgent) {
            requireNonNull(rpcEndpointName, "rpcName is required");
            requireNonNull(userAgent, "userAgent is required");

            usageData
                    .computeIfAbsent(rpcEndpointName, __ -> new ConcurrentHashMap<>())
                    .computeIfAbsent(userAgent, __ -> new LongAdder())
                    .increment();
        }
    }
}
