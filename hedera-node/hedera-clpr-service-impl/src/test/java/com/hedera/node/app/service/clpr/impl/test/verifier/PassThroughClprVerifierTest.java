// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PassThroughClprVerifierTest {

    private final PassThroughClprVerifier subject = new PassThroughClprVerifier();

    @Test
    @DisplayName("verifyConfig parses valid protobuf and preserves the embedded trust anchor")
    void verifyConfigParsesValidProtobuf() {
        final var anchor = Bytes.wrap(new byte[] {0x0a, 0x0b, 0x0c});
        final var config = ClprLedgerConfiguration.newBuilder()
                .chainId("hiero:testnet")
                .serviceAddress(Bytes.wrap(new byte[] {1, 2, 3}))
                .initialTrustAnchor(anchor)
                .initialTrustAnchorId(anchor)
                .build();
        final var bytes = ClprLedgerConfiguration.PROTOBUF.toBytes(config);

        final var verified = subject.verifyConfig(bytes, Bytes.EMPTY, Bytes.EMPTY, null);
        final var result = verified.config();

        assertThat(result.chainId()).isEqualTo("hiero:testnet");
        assertThat(result.serviceAddress()).isEqualTo(Bytes.wrap(new byte[] {1, 2, 3}));
        assertThat(result.initialTrustAnchor()).isEqualTo(anchor);
        assertThat(result.initialTrustAnchorId()).isEqualTo(anchor);
        // Empty manifest proof yields a well-formed empty manifest bound to the config's service_address.
        assertThat(verified.manifest().version()).isEqualTo(1L);
        assertThat(verified.manifest().serviceAddress()).isEqualTo(Bytes.wrap(new byte[] {1, 2, 3}));
        assertThat(verified.manifest().endpoints()).isEmpty();
    }

    @Test
    @DisplayName("verifyConfig rejects malformed bytes")
    void verifyConfigRejectsMalformed() {
        final var garbage = Bytes.wrap(new byte[] {0x7F, 0x7F});

        assertThatThrownBy(() -> subject.verifyConfig(garbage, Bytes.EMPTY, Bytes.EMPTY, null))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @Test
    @DisplayName("verifyBundle parses valid protobuf")
    void verifyBundleParsesValidProtobuf() {
        final var bundle = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(5)
                        .sentRunningHash(Bytes.wrap(new byte[32]))
                        .receivedMessageId(3)
                        .status(ClprChannelStatus.ACTIVE)
                        .build())
                .messages(List.of())
                .build();
        final var bytes = ClprBundleContent.PROTOBUF.toBytes(bundle);

        final var result = subject.verifyBundle(bytes, Bytes.EMPTY, Bytes.EMPTY, null);

        assertThat(result.metadata().nextMessageId()).isEqualTo(5L);
        assertThat(result.metadata().receivedMessageId()).isEqualTo(3L);
        assertThat(result.messages()).isEmpty();
    }

    @Test
    @DisplayName("verifyBundle rejects malformed bytes")
    void verifyBundleRejectsMalformed() {
        final var garbage = Bytes.wrap(new byte[] {0x7F, 0x7F});

        assertThatThrownBy(() -> subject.verifyBundle(garbage, Bytes.EMPTY, Bytes.EMPTY, null))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }
}
