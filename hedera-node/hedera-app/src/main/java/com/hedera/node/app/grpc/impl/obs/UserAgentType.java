package com.hedera.node.app.grpc.impl.obs;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

public enum UserAgentType {
    HIERO_SDK_CPP("HieroSdkCpp", "hiero-sdk-cpp"),
    HIERO_SDK_GO("HieroSdkGo", "hiero-sdk-go"),
    HIERO_SDK_JAVA("HieroSdkJava", "hiero-sdk-java"),
    HIERO_SDK_JS("HieroSdkJs", "hiero-sdk-js"),
    HIERO_SDK_PYTHON("HieroSdkPython", "hiero-sdk-python"),
    HIERO_SDK_RUST("HieroSdkRust", "hiero-sdk-rust"),
    HIERO_SDK_SWIFT("HieroSdkSwift", "hiero-sdk-swift"),
    OTHER("Other"),
    UNKNOWN("Unknown");

    private static final Map<String, UserAgentType> values = new HashMap<>();
    static {
        for (final UserAgentType userAgent : values()) {
            values.put(userAgent.key, userAgent);
            if (userAgent.alternatives != null) {
                for (final String alt : userAgent.alternatives) {
                    values.put(alt.toLowerCase(), userAgent);
                }
            }
        }
    }

    private final String key;
    private final String[] alternatives;

    UserAgentType(@NonNull final String key, final String... alternatives) {
        this.key = requireNonNull(key);
        this.alternatives = requireNonNull(alternatives);
    }

    public String key() {
        return key;
    }

    public static UserAgentType fromString(@Nullable String string) {
        if (string == null || string.isBlank()) {
            // No user agent was specified
            return UNKNOWN;
        }

        string = string.trim();

        UserAgentType userAgent = values.get(string);
        if (userAgent != null) {
            return userAgent;
        }

        userAgent = values.get(string.toLowerCase());
        if (userAgent != null) {
            return userAgent;
        }

        // There was a user agent, but it is not a known one
        return OTHER;
    }
}
