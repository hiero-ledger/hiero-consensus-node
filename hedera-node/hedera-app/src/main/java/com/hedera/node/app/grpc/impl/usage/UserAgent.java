// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Record that represents a user-agent.
 *
 * @param agentType the type of user-agent
 * @param version the version of the user-agent used
 */
public record UserAgent(@NonNull UserAgentType agentType, @NonNull String version) {
    // Simple SemVer regex, taken from https://ihateregex.io/expr/semver/
    private static final Pattern VERSION_REGEX = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private static final String UNKNOWN_STR = "Unknown";
    static final UserAgent UNKNOWN = new UserAgent(UserAgentType.UNKNOWN, UNKNOWN_STR);
    static final UserAgent UNSPECIFIED = new UserAgent(UserAgentType.UNSPECIFIED, UNKNOWN_STR);

    private static final Logger logger = LogManager.getLogger(UserAgent.class);

    public UserAgent {
        requireNonNull(agentType, "agentType is required");
        requireNonNull(version, "version is required");
    }

    /**
     * Parses the specified user-agent string into a known user-agent object. If the agent type is not a known type
     * (i.e. not a recognized user-agent) then an {@code UNKNOWN} or {@code UNSPECIFIED} agent type will be returned.
     *
     * @param userAgentStr the user-agent string to parse
     * @return a user-agent object representing the parsed user-agent string provided
     */
    public static @NonNull UserAgent from(@Nullable final String userAgentStr) {
        if (userAgentStr == null || userAgentStr.isBlank()) {
            return UserAgent.UNSPECIFIED;
        }

        UserAgent userAgent = null;

        /*
        If we are following the HTTP standard for user-agent header, then there could be multiple components in the
        header, separated by spaces like "hiero-sdk-java/1.2.3 foo-bar/34 Hashgraph". We only care about the piece
        that relates to known user-agents, so we have to parse each piece and check if it is valid
         */
        final String[] tokens = userAgentStr.split("\\s"); // split on spaces
        for (final String token : tokens) {
            final String[] subTokens = token.split("/"); // split on forward-slash '/'
            if (subTokens.length == 0 && userAgent == null) {
                // the user-agent is missing
                userAgent = UserAgent.UNSPECIFIED;
                continue;
            } else if (subTokens.length > 2 && userAgent == null) {
                // the user-agent is not formatted properly
                userAgent = UserAgent.UNKNOWN;
                continue;
            }

            final UserAgentType type = UserAgentType.fromString(subTokens[0]);
            // If the version is blank/missing or the type is a known type, then set the version to unknown
            final String rawVersion = subTokens.length == 1 ? null : subTokens[1];
            final String version;
            if (rawVersion == null || rawVersion.isBlank() || !type.isKnownType()) {
                version = UNKNOWN_STR;
            } else if ("dev".equalsIgnoreCase(rawVersion)) {
                version = "dev";
            } else if (VERSION_REGEX.matcher(rawVersion).matches()) {
                version = rawVersion.toLowerCase();
            } else {
                version = UNKNOWN_STR;
            }

            if (userAgent == null) {
                userAgent = new UserAgent(type, version);
            } else if (type.isKnownType() && userAgent.agentType.isKnownType()) {
                // we just parsed a known user-agent AND we parsed another known user-agent previously
                // because of this, we now have multiple types and can't be certain what is real
                logger.warn("Multiple known user-agent types found: {}", userAgentStr);
                userAgent = UserAgent.UNKNOWN;
            } else if (!type.isKnownType() && userAgent.agentType.isKnownType()) {
                // do not override the already known agent type
            } else if (type.isKnownType()) {
                // we previously captured an unknown agent type, now replace it with the known type we parsed
                userAgent = new UserAgent(type, version);
            }
        }

        if (userAgent == null) {
            // we didn't find a properly formatted user-agent
            userAgent = UserAgent.UNSPECIFIED;
        }

        if (!userAgent.agentType.isKnownType()) {
            logger.debug("Unknown user-agent detected: {}", userAgentStr);
        }

        return userAgent;
    }
}
