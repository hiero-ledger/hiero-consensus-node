// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.grpc.impl.GrpcLoggingInterceptor.UserAgentType;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GrpcLoggingInterceptorTest {

    private static final Key<String> userAgentHeaderKey = Key.of("X-User-Agent", Metadata.ASCII_STRING_MARSHALLER);

    static LogCaptor accessLogCaptor = new LogCaptor(LogManager.getLogger("grpc-access-log"));

    @AfterEach
    void afterEach() {
        accessLogCaptor.stopCapture();
        accessLogCaptor = new LogCaptor(LogManager.getLogger("grpc-access-log"));
    }

    @AfterAll
    static void afterAll() {
        accessLogCaptor.stopCapture();
    }

    @ParameterizedTest
    @MethodSource("testRpcNameArgs")
    void testRpcName(final String fullMethodName, final String expectedServiceName, final String expectedMethodName) {
        final GrpcLoggingInterceptor interceptor = new GrpcLoggingInterceptor();
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final Metadata metadata = new Metadata();
        metadata.put(userAgentHeaderKey, "hiero-sdk-java/2.1.3");
        final ServerCallHandler<String, String> callHandler = mock(ServerCallHandler.class);
        final MethodDescriptor<String, String> descriptor = newDescriptor(fullMethodName);

        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);

        interceptor.interceptCall(serverCall, metadata, callHandler);

        final List<String> accessLogs = accessLogCaptor.infoLogs();
        assertEquals(1, accessLogs.size());
        final String log = accessLogs.getFirst();
        assertTrue(log.contains("service=" + expectedServiceName));
        assertTrue(log.contains("method=" + expectedMethodName));
    }

    static List<Arguments> testRpcNameArgs() {
        return List.of(
                Arguments.of("proto.MyService/save", "MyService", "Save"),
                Arguments.of("", "Unknown", "Unknown"),
                // MethodDescriptor always expects a forward slash '/' in the full name, without it, it can't parse it
                Arguments.of("proto.MyService", "Unknown", "Unknown"),
                Arguments.of("MyService/save", "MyService", "Save"),
                Arguments.of("proto.MyService/", "MyService", "Unknown"),
                Arguments.of("proto.MyService/saveAndCommit", "MyService", "SaveAndCommit"),
                Arguments.of("/save", "Unknown", "Save"));
    }

    @ParameterizedTest
    @MethodSource("testUserAgentArgs")
    void testUserAgent(final String userAgent, final String expectedAgentType, final String expectedAgentVersion) {
        final GrpcLoggingInterceptor interceptor = new GrpcLoggingInterceptor();
        final ServerCall<String, String> serverCall = mock(ServerCall.class);
        final Metadata metadata = new Metadata();
        if (userAgent != null) {
            metadata.put(userAgentHeaderKey, userAgent);
        }
        final ServerCallHandler<String, String> callHandler = mock(ServerCallHandler.class);
        final MethodDescriptor<String, String> descriptor = newDescriptor("proto.TestService/saveAndCommit");

        when(serverCall.getMethodDescriptor()).thenReturn(descriptor);

        interceptor.interceptCall(serverCall, metadata, callHandler);

        final List<String> accessLogs = accessLogCaptor.infoLogs();
        assertEquals(1, accessLogs.size());
        final String log = accessLogs.getFirst();
        assertTrue(
                log.contains("uaType=" + expectedAgentType),
                "Expected user-agent type of '" + expectedAgentType + "' in log: '" + log + "'");
        assertTrue(
                log.contains("uaVersion=" + expectedAgentVersion),
                "Expected user-agent version of '" + expectedAgentVersion + "' in log: '" + log + "'");
    }

    static List<Arguments> testUserAgentArgs() {
        return List.of(
                Arguments.of("hiero-sdk-cpp/1.1.1", "HieroSdkCpp", "1.1.1"),
                Arguments.of("hiero-sdk-go/1.1.2", "HieroSdkGo", "1.1.2"),
                Arguments.of("hiero-sdk-java/1.1.3", "HieroSdkJava", "1.1.3"),
                Arguments.of("hiero-sdk-js/1.1.4", "HieroSdkJs", "1.1.4"),
                Arguments.of("hiero-sdk-python/1.1.5", "HieroSdkPython", "1.1.5"),
                Arguments.of("hiero-sdk-rust/1.1.6", "HieroSdkRust", "1.1.6"),
                Arguments.of("hiero-sdk-swift/1.1.7", "HieroSdkSwift", "1.1.7"),
                Arguments.of("hiero-sdk-lua/1.1.8", "Unknown", "Unknown"),
                Arguments.of("hiero-sdk-lua", "Unknown", "Unknown"),
                Arguments.of("hiero-sdk-java", "HieroSdkJava", "Unknown"),
                Arguments.of(null, "Unknown", "Unknown"),
                Arguments.of("", "Unknown", "Unknown"),
                Arguments.of("/1.1.3", "Unknown", "Unknown"),
                Arguments.of("hiero-sdk-lua/1.1.3 foo-bar/42 baz", "Unknown", "Unknown"),
                Arguments.of("/", "Unknown", "Unknown"),
                Arguments.of("foo-bar/2 hiero-sdk-java/1.2.3", "HieroSdkJava", "1.2.3"),
                Arguments.of("hiero-sdk-java/1.2.3/foo", "Unknown", "Unknown"),
                Arguments.of("hiero-sdk-java/dev", "HieroSdkJava", "dev"),
                Arguments.of("hiero-sdk-java/3.0.0-beta1", "HieroSdkJava", "3.0.0-beta1"),
                Arguments.of("hiero-sdk-java/2.1.0 hiero-sdk-java/2.1.0", "Unknown", "Unknown"));
    }

    @ParameterizedTest
    @MethodSource("testUserAgentTypeArgs")
    void testUserAgentType(final String userAgentType, final UserAgentType expectedType) {
        final UserAgentType actualType = UserAgentType.fromString(userAgentType);
        assertEquals(expectedType, actualType);
    }

    static List<Arguments> testUserAgentTypeArgs() {
        return List.of(
                Arguments.of(null, UserAgentType.UNKNOWN),
                Arguments.of("", UserAgentType.UNKNOWN),
                Arguments.of("  ", UserAgentType.UNKNOWN),
                Arguments.of(" hiero-sdk-java   ", UserAgentType.HIERO_SDK_JAVA),
                Arguments.of("Hiero-Sdk-Java", UserAgentType.HIERO_SDK_JAVA),
                Arguments.of("grpc-sdk-java", UserAgentType.OTHER));
    }

    static MethodDescriptor<String, String> newDescriptor(final String fullMethodName) {
        final Marshaller<String> marshaller = mock(Marshaller.class);
        return MethodDescriptor.newBuilder(marshaller, marshaller)
                .setType(MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .build();
    }
}
