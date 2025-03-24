// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This is a GRPC server interceptor that is used to log usage information for incoming RPC calls.
 */
public class GrpcLoggingInterceptor implements ServerInterceptor {

    /**
     * General purpose logger to be used within this class.
     */
    private static final Logger clsLogger = LogManager.getLogger(GrpcLoggingInterceptor.class);

    /**
     * Logger used to write GRPC access information to a unique log file.
     */
    private static final Logger accessLogger = LogManager.getLogger("grpc-access-log");

    /**
     * Key used to extract the {@code X-User-Agent} header from the GRPC metadata.
     */
    private static final Key<String> userAgentHeaderKey = Key.of("X-User-Agent", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * String used for when a component of the user-agent or RPC call cannot be determined.
     */
    private static final String UNKNOWN = "Unknown";

    /**
     * Simple cache used to hold mappings between long-form RPC names to formatted service and method names.
     * <p>For example, a mapping may be:
     * {@code "proto.MyService/commitTransaction" -> RpcName("MyService", "CommitTransaction")}
     */
    private static final ConcurrentMap<String, RpcName> rpcNameCache = new ConcurrentHashMap<>();

    /**
     * Simple cache used to hold mappings of raw user-agent strings to parsed/sanitized user-agent parts.
     * <p>For example, a mapping may be:
     * {@code "hiero-sdk-java/2.1.3 foo-bar/16 baz" -> UserAgent(UserAgentType.HIERO_SDK_JAVA, "2.1.3")}
     */
    private static final ConcurrentMap<String, UserAgent> userAgentCache = new ConcurrentHashMap<>();

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
            final ServerCall<ReqT, RespT> serverCall,
            final Metadata metadata,
            final ServerCallHandler<ReqT, RespT> serverCallHandler) {

        final MethodDescriptor<?, ?> descriptor = serverCall.getMethodDescriptor();
        final RpcName rpcName = getRpcName(descriptor);
        final UserAgent userAgent = getUserAgent(metadata);

        accessLogger.info(
                "grpc-call service={}, method={}, uaType={}, uaVersion={}",
                rpcName.serviceName,
                rpcName.methodName,
                userAgent.agentType.id,
                userAgent.version);

        return serverCallHandler.startCall(serverCall, metadata);
    }

    /**
     * Extract the user-agent from the {@code X-User-Agent} header and parse it (or retrieve the parsed version from
     * the cache).
     *
     * @param metadata the metadata that may have the user-agent information
     * @return the parsed user-agent
     */
    private @NonNull UserAgent getUserAgent(@NonNull final Metadata metadata) {
        requireNonNull(metadata, "metadata is required");

        final String userAgentStr = metadata.get(userAgentHeaderKey);

        if (userAgentStr == null || userAgentStr.isBlank()) {
            // the user-agent is missing, escape early and don't cache it
            return UserAgent.UNKNOWN;
        }

        UserAgent userAgent = userAgentCache.get(userAgentStr);
        if (userAgent != null) {
            // we have it cached, escape early
            return userAgent;
        }

        /*
        The user-agent is not in the cache, so now we need to parse it.
        If we are following the HTTP standard for user-agent header, then there could be multiple components in the
        header, separated by spaces like "hiero-sdk-java/1.2.3 foo-bar/34 Hashgraph". We only care about the piece
        that relates to known user-agents, so we have to parse each piece and check if it is valid
         */
        final String[] tokens = userAgentStr.split("\\s"); // split on spaces
        for (final String token : tokens) {
            final String[] subTokens = token.split("/"); // split on forward-slash '/'
            if (subTokens.length == 0 || subTokens.length > 2) {
                continue;
            }

            final UserAgentType type = UserAgentType.fromString(subTokens[0]);
            final String version = subTokens.length == 1 || subTokens[1].isBlank() ? UNKNOWN : subTokens[1];

            if (type.isKnownType) {
                if (userAgent == null) {
                    userAgent = new UserAgent(type, version);
                } else if (userAgent.agentType.isKnownType) {
                    // we just parsed a known user-agent AND we parsed another known user-agent previously
                    // because of this, we now have multiple types and can't be certain what is real
                    clsLogger.warn("Multiple known user-agent types found: {}", userAgentStr);
                    userAgent = UserAgent.UNKNOWN;
                }
            }
        }

        if (userAgent == null) {
            // we didn't find a properly formatted user-agent
            userAgent = UserAgent.UNKNOWN;
        }

        if (!userAgent.agentType.isKnownType) {
            clsLogger.debug("Unknown user-agent detected: {}", userAgentStr);
        }

        userAgentCache.put(userAgentStr, userAgent);
        return userAgent;
    }

    /**
     * Get the RPC name (service and method) from the specified RPC method descriptor.
     *
     * @param descriptor the method descriptor to use
     * @return the parsed/formatted RPC name
     */
    private RpcName getRpcName(@NonNull final MethodDescriptor<?, ?> descriptor) {
        requireNonNull(descriptor, "descriptor is required");

        RpcName rpcName = rpcNameCache.get(descriptor.getFullMethodName());

        if (rpcName != null) {
            // we have it cached, escape early
            return rpcName;
        }

        String svcName = descriptor.getServiceName();
        String methodName = descriptor.getBareMethodName();

        if (svcName == null || svcName.isBlank()) {
            svcName = UNKNOWN;
        } else if (svcName.startsWith("proto.")) {
            // remove "proto." from service name
            svcName = svcName.substring(6);
        }

        if (methodName == null || methodName.isBlank()) {
            methodName = UNKNOWN;
        } else {
            // capitalize first letter of method
            methodName = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
        }

        // combine and store
        rpcName = new RpcName(svcName, methodName);
        rpcNameCache.put(descriptor.getFullMethodName(), rpcName);

        return rpcName;
    }

    private record UserAgent(@NonNull UserAgentType agentType, @NonNull String version) {
        static final UserAgent UNKNOWN = new UserAgent(UserAgentType.UNKNOWN, GrpcLoggingInterceptor.UNKNOWN);

        UserAgent {
            requireNonNull(agentType, "agentType is required");
            requireNonNull(version, "version is required");
        }
    }

    private record RpcName(@NonNull String serviceName, @NonNull String methodName) {
        RpcName {
            requireNonNull(serviceName, "serviceName is required");
            requireNonNull(methodName, "methodName is required");
        }
    }

    /**
     * List of known user-agent types (e.g. SDKs).
     */
    enum UserAgentType {
        HIERO_SDK_CPP("HieroSdkCpp", true, "hiero-sdk-cpp"),
        HIERO_SDK_GO("HieroSdkGo", true, "hiero-sdk-go"),
        HIERO_SDK_JAVA("HieroSdkJava", true, "hiero-sdk-java"),
        HIERO_SDK_JS("HieroSdkJs", true, "hiero-sdk-js"),
        HIERO_SDK_PYTHON("HieroSdkPython", true, "hiero-sdk-python"),
        HIERO_SDK_RUST("HieroSdkRust", true, "hiero-sdk-rust"),
        HIERO_SDK_SWIFT("HieroSdkSwift", true, "hiero-sdk-swift"),
        OTHER("Other", false),
        UNKNOWN(GrpcLoggingInterceptor.UNKNOWN, false);

        private static final Map<String, UserAgentType> values = new HashMap<>();

        static {
            for (final UserAgentType userAgent : values()) {
                values.put(userAgent.id, userAgent);
                if (userAgent.variations != null) {
                    for (final String altName : userAgent.variations) {
                        values.put(altName.toLowerCase(), userAgent);
                    }
                }
            }
        }

        /**
         * The "properly" formatted ID/name of the user-agent
         */
        private final String id;

        /**
         * List of variations that all map to the same user-agent
         */
        private final String[] variations;

        /**
         * Is this user-agent a 'known' type? (that is, it is one of our known SDKs)
         */
        private final boolean isKnownType;

        UserAgentType(@NonNull final String id, final boolean isKnownType, final String... variations) {
            this.id = requireNonNull(id);
            this.isKnownType = isKnownType;
            this.variations = requireNonNull(variations);
        }

        /**
         * Parses the specified user-agent string into one of the known user-agent IDs. If the specified user-agent is
         * missing, then {@link UserAgentType#UNKNOWN} is returned. If a user-agent was provided, but it doesn't match
         * any of the known user-agents, then {@link UserAgentType#OTHER} is returned.
         *
         * @param userAgentType the user-agent string to parse
         * @return the user-agent type
         */
        static @NonNull UserAgentType fromString(@Nullable String userAgentType) {
            if (userAgentType == null || userAgentType.isBlank()) {
                // No user-agent was specified
                return UNKNOWN;
            }

            userAgentType = userAgentType.trim();

            UserAgentType type = values.get(userAgentType);
            if (type != null) {
                return type;
            }

            type = values.get(userAgentType.toLowerCase());
            if (type != null) {
                return type;
            }

            // There was a user-agent present, but it doesn't match any one that we know
            return OTHER;
        }
    }
}
