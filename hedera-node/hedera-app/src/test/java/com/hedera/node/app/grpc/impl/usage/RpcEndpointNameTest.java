// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RpcEndpointNameTest {

    @ParameterizedTest
    @MethodSource("testRpcNameArgs")
    void testRpcName(final String fullMethodName, final String expectedService, final String expectedMethod) {
        final MethodDescriptor<?, ?> descriptor = newDescriptor(fullMethodName);
        final RpcEndpointName rpcEndpointName = RpcEndpointName.from(descriptor);
        assertThat(rpcEndpointName.serviceName()).isEqualTo(expectedService);
        assertThat(rpcEndpointName.methodName()).isEqualTo(expectedMethod);
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

    static MethodDescriptor<String, String> newDescriptor(final String fullMethodName) {
        final Marshaller<String> marshaller = mock(Marshaller.class);
        return MethodDescriptor.newBuilder(marshaller, marshaller)
                .setType(MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .build();
    }
}
