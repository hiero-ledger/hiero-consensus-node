package com.hedera.node.app.grpc.impl.obs;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Interceptor for GRPC calls received by the server. This interceptor captures the user-agent and service/method being
 * invoked and records them as a metric.
 */
public class ObservabilityInterceptor implements ServerInterceptor {

    private static final String CATEGORY = "grpc";
    private static final Key<String> USER_AGENT_KEY = Key.of("X-User-Agent", Metadata.ASCII_STRING_MARSHALLER);

    private final Metrics metrics;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> rpcNames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UserAgent> userAgents = new ConcurrentHashMap<>();

    public ObservabilityInterceptor(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> serverCall, final Metadata metadata,
            final ServerCallHandler<ReqT, RespT> serverCallHandler) {

        final MethodDescriptor<ReqT, RespT> methodDescriptor = serverCall.getMethodDescriptor();
        final String rpcName = getRpcName(methodDescriptor);
        final UserAgent userAgent = getUserAgent(metadata.get(USER_AGENT_KEY));

        final Counter counter = getCounter(userAgent, rpcName);
        counter.increment();

        System.out.println("OBS Interceptor:: userAgent=" + userAgent + ", rpcName=" + rpcName
                + ", counter=" + counter.getName());

        return serverCallHandler.startCall(serverCall, metadata);
    }

    private Counter getCounter(final UserAgent userAgent, final String rpcName) {
        // metric name format: <RpcName>_<UserAgentType>_<UserAgentVersion>
        final String metricName = rpcName + '_' + userAgent.type.key() + '_' + userAgent.version.key();
        return counters.computeIfAbsent(metricName, __ -> {
            final Counter.Config config = new Counter.Config(CATEGORY, metricName)
                    .withUnit("invocations")
                    .withDescription("The number of invocations made to RPC method " + rpcName + " from "
                            + userAgent.type.key() + " (version " + userAgent.version.key() + ")");
            return metrics.getOrCreate(config);
        });
    }

    private UserAgent getUserAgent(final String rawUserAgent) {
        UserAgent userAgent = userAgents.get(rawUserAgent);
        if (userAgent != null) {
            return userAgent;
        }

        // we don't have the user agent cached, so parse it
        // these are formatted as "<sdk-type>/<sdk-version>[ <other-type>/<other-version>]"
        String userAgentStr = rawUserAgent;
        final int sepIdx = rawUserAgent.indexOf(' ');
        if (sepIdx != -1) {
            // if the user agent has multiple parts, extract just the first one
            userAgentStr = userAgentStr.substring(0, sepIdx);
        }

        final int typeVersionSepIdx = userAgentStr.indexOf('/');
        if (typeVersionSepIdx == -1) {
            return new UserAgent(UserAgentType.UNKNOWN, UserAgentVersion.UNKNOWN);
        }

        final String agentTypeStr = userAgentStr.substring(0, typeVersionSepIdx);
        final String agentVersionStr = userAgentStr.substring(typeVersionSepIdx + 1);
        final UserAgentType userAgentType = UserAgentType.fromString(agentTypeStr);
        final UserAgentVersion userAgentVersion = getVersionLabel(userAgentType, agentVersionStr);

        userAgent = new UserAgent(userAgentType, userAgentVersion);
        userAgents.put(rawUserAgent, userAgent);
        return userAgent;
    }

    private record UserAgent(UserAgentType type, UserAgentVersion version) { }

    private UserAgentVersion getVersionLabel(final UserAgentType userAgent, final String version) {
        if (version == null || version.isBlank()) {
            return UserAgentVersion.UNKNOWN;
        }
        if (version.contains("dev") || version.contains("beta")) {
            return UserAgentVersion.PRE_RELEASE;
        }
        // TODO: add latest/old determination
        return UserAgentVersion.LATEST;
    }

    private String getRpcName(final MethodDescriptor<?, ?> descriptor) {
        String rpcName = rpcNames.get(descriptor.getFullMethodName());

        if (rpcName != null) {
            return rpcName;
        }

        String svcName = descriptor.getServiceName();
        String methodName = descriptor.getBareMethodName();

        // remove "proto." from service name
        if (svcName.startsWith("proto.")) {
            svcName = svcName.substring(6);
        }

        // capitalize first letter of method
        methodName = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);

        // combine and store
        rpcName = svcName + '_' + methodName;
        rpcNames.put(descriptor.getFullMethodName(), rpcName);

        return rpcName;
    }

}
