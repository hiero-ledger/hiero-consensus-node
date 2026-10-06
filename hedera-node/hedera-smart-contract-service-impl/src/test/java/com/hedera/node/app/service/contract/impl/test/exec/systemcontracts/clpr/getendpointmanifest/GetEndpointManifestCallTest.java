// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.getendpointmanifest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetEndpointManifestCallTest extends CallTestBase {

    @Mock
    private ReadableEndpointManifestStore manifestStore;

    @Test
    @DisplayName("should return ABI-encoded PBJ manifest bytes")
    void returnsAbiEncodedManifest() {
        final var manifest = ClprEndpointManifest.newBuilder().version(3L).build();
        given(nativeOperations.readableEndpointManifestStore()).willReturn(manifestStore);
        given(manifestStore.get()).willReturn(manifest);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

        final byte[] expectedPbj =
                ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        final byte[] expectedAbi = GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST
                .getOutputs()
                .encode(Tuple.singleton(expectedPbj))
                .array();
        assertThat(result.fullResult().result().output()).isEqualTo(org.apache.tuweni.bytes.Bytes.wrap(expectedAbi));
    }

    @Test
    @DisplayName("should succeed for a DEFAULT (empty) manifest")
    void returnsForDefaultManifest() {
        given(nativeOperations.readableEndpointManifestStore()).willReturn(manifestStore);
        given(manifestStore.get()).willReturn(ClprEndpointManifest.DEFAULT);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    }

    @Test
    @DisplayName("should allow static frame")
    void allowsStaticFrame() {
        assertThat(createSubject().allowsStaticFrame()).isTrue();
    }

    private GetEndpointManifestCall createSubject() {
        return new GetEndpointManifestCall(mockEnhancement(), gasCalculator);
    }
}
