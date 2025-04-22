// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Enum containing the known types of SDKs, along with values for cases where they SDK type is not known or not specified.
 */
public enum UserAgentType {
    HIERO_SDK_CPP("HieroSdkCpp", true, "hiero-sdk-cpp"),
    HIERO_SDK_GO("HieroSdkGo", true, "hiero-sdk-go"),
    HIERO_SDK_JAVA("HieroSdkJava", true, "hiero-sdk-java"),
    HIERO_SDK_JS("HieroSdkJs", true, "hiero-sdk-js"),
    HIERO_SDK_PYTHON("HieroSdkPython", true, "hiero-sdk-python"),
    HIERO_SDK_RUST("HieroSdkRust", true, "hiero-sdk-rust"),
    HIERO_SDK_SWIFT("HieroSdkSwift", true, "hiero-sdk-swift"),
    UNSPECIFIED("Unspecified", false),
    UNKNOWN("Unknown", false);

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
     * Flag to indicate if this is a "known" type (i.e. it is one of our SDKs). When parsing a string into a type,
     * if it resolves to a type where this is true, it means that it matched one of the variations associated with this
     * type.
     */
    private final boolean isKnownType;

    UserAgentType(@NonNull final String id, final boolean isKnownType, final String... variations) {
        this.id = requireNonNull(id);
        this.isKnownType = isKnownType;
        this.variations = requireNonNull(variations);
    }

    /**
     * @return true if this type is a known SDK, else false
     */
    public boolean isKnownType() {
        return isKnownType;
    }

    /**
     * @return the formatted ID/name associated with this type
     */
    public String id() {
        return id;
    }

    /**
     * Parses the specified user-agent string into one of the known user-agent IDs. If the specified user-agent is
     * missing, then {@link UserAgentType#UNSPECIFIED} is returned. If a user-agent was provided, but it doesn't match
     * any of the known user-agents (or their variants), then {@link UserAgentType#UNKNOWN} is returned.
     *
     * @param userAgentType the user-agent string to parse
     * @return the user-agent type
     */
    static @NonNull UserAgentType fromString(@Nullable final String userAgentType) {
        if (userAgentType == null || userAgentType.isBlank()) {
            // No user-agent was specified
            return UNSPECIFIED;
        }

        final String trimmedAgentType = userAgentType.trim();

        UserAgentType type = values.get(trimmedAgentType);
        if (type != null) {
            return type;
        }

        type = values.get(trimmedAgentType.toLowerCase());
        if (type != null) {
            return type;
        }

        // There was a user-agent present, but it doesn't match any one that we know
        return UNKNOWN;
    }
}
