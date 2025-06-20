// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.hedera.node.app.grpc.impl.GrpcTestBase;
import com.hedera.node.app.grpc.impl.netty.NettyGrpcServerManager;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class GrpcInterceptorTest extends GrpcTestBase {

    static final VarHandle usageTrackerHandle;

    static {
        try {
            usageTrackerHandle = MethodHandles.privateLookupIn(NettyGrpcServerManager.class, MethodHandles.lookup())
                    .findVarHandle(NettyGrpcServerManager.class, "usageTracker", GrpcUsageTracker.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    static LogCaptor accessLogCaptor = new LogCaptor(LogManager.getLogger("grpc-access-log"));

    private static final String SERVICE = "proto.TestService";
    private static final String METHOD = "testMethod";
    private static final String GOOD_RESPONSE = "All Good";
    private static final byte[] GOOD_RESPONSE_BYTES = GOOD_RESPONSE.getBytes(StandardCharsets.UTF_8);
    private static final IngestWorkflow GOOD_INGEST = (req, res) -> res.writeBytes(GOOD_RESPONSE_BYTES);
    private static final QueryWorkflow UNIMPLEMENTED_QUERY = (r, r2) -> fail("The Query should not be called");

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
    @MethodSource("testUserAgentArgs")
    void testUserAgent(final String userAgent, final UserAgentType expectedAgentType, final String expectedVersion) {
        registerIngest(METHOD, GOOD_INGEST, UNIMPLEMENTED_QUERY, UNIMPLEMENTED_QUERY);
        startServer(false, userAgent);

        send(SERVICE, METHOD, "And now for a message from our sponsors...");

        // force logging of usage data
        final GrpcUsageTracker usageTracker = (GrpcUsageTracker) usageTrackerHandle.get(grpcServer);
        usageTracker.logAndResetUsageData();

        final List<String> accessLogs = accessLogCaptor.infoLogs();
        assertThat(accessLogs).hasSize(1);
        final String expectedLog = "|service=TestService|method=TestMethod|sdkType=" + expectedAgentType.id()
                + "|sdkVersion=" + expectedVersion + "|count=1|";
        final String actualLog = accessLogs.getFirst();
        assertThat(actualLog).contains(expectedLog);
    }

    static List<Arguments> testUserAgentArgs() {
        /*
        This is not an exhaustive list of possible permutations. A more thorough list can be found in UserAgentTest and
        RpcEndpointNameTest. This test is just for testing the interaction with a more real GRPC client/server.
         */
        return List.of(
                Arguments.of("hiero-sdk-java/1.1.0", UserAgentType.HIERO_SDK_JAVA, "1.1.0"),
                Arguments.of("foo/bar hiero-sdk-java/3 baz", UserAgentType.HIERO_SDK_JAVA, "3"),
                Arguments.of(null, UserAgentType.UNSPECIFIED, "Unknown"));
    }
}
