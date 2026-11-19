// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.net.InetAddresses;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import io.grpc.ManagedChannel;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks client reuse and endpoint validation without creating network resources. */
class ClprEndpointClientCacheTest {
    private static final String HOST = "8.8.8.8";
    private static final int PORT = 50211;
    private static final InetSocketAddress ADDRESS = ClprEndpointAddressPolicy.addressFor(HOST, PORT, Set.of());

    private final ConfigProvider configProvider = mock(ConfigProvider.class);
    private final VersionedConfiguration configuration = mock(VersionedConfiguration.class);
    private final ClprConfig clprConfig = mock(ClprConfig.class);
    private static final String SOLO_HOST = "network-node1.test.svc.cluster.local";
    private final ClprEndpointClientCache.HostResolver resolver = mock(ClprEndpointClientCache.HostResolver.class);
    private final ClprEndpointClientCache cache = new ClprEndpointClientCache(configProvider, resolver);

    @BeforeEach
    void setUp() {
        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(clprConfig);
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of());
        when(clprConfig.requireNumericPeerEndpoints()).thenReturn(true);
    }

    @Test
    void reusesClientWhenTransportIsUnchanged() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(ADDRESS), anyBoolean()))
                    .thenReturn(channel);
            final var first = cache.clientFor(HOST, PORT, true);
            assertThat(cache.clientFor(HOST, PORT, true)).isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, true), times(1));
        }
    }

    @Test
    void rebuildsClientAndClosesOldChannelWhenTransportChanges() {
        final var oldChannel = mock(ManagedChannel.class);
        final var newChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(ADDRESS), anyBoolean()))
                    .thenReturn(oldChannel, newChannel);
            final var first = cache.clientFor(HOST, PORT, false);
            assertThat(cache.clientFor(HOST, PORT, true)).isNotSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false), times(1));
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, true), times(1));
            verify(oldChannel).shutdown();
        }
    }

    @Test
    void shutdownAllClosesChannelsAndClearsCache() {
        final var channelA = mock(ManagedChannel.class);
        final var channelB = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(any(InetSocketAddress.class), anyBoolean()))
                    .thenReturn(channelA, channelB, mock(ManagedChannel.class));
            cache.clientFor(HOST, PORT, false);
            cache.clientFor(HOST, PORT + 1, false);
            cache.shutdownAll();
            verify(channelA).shutdown();
            verify(channelB).shutdown();
            cache.clientFor(HOST, PORT, false);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false), times(2));
        }
    }

    @Test
    void shutdownAllOnEmptyCacheDoesNotThrow() {
        assertThatNoException().isThrownBy(cache::shutdownAll);
    }

    @Test
    void shutdownAllIsIdempotent() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(channel);
            cache.clientFor(HOST, PORT, false);
            cache.shutdownAll();
            cache.shutdownAll();
            verify(channel, times(1)).shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "peer.example", "127.0.0.1", "169.254.169.254", "10.0.0.1", "::1", "fd00::1"})
    void rejectsUnpermittedEndpointBeforeCreatingChannel(final String host) {
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> cache.clientFor(host, PORT, false)).isInstanceOf(ClientException.class);
            mocked.verifyNoInteractions();
            verifyNoInteractions(resolver);
        }
    }

    @Test
    void dnsPeersUseValidatedNumericAddressesAndReplaceChannelsWhenTheAddressChanges() throws Exception {
        when(clprConfig.requireNumericPeerEndpoints()).thenReturn(false);
        final var cidrs = Set.of("10.0.0.0/24");
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(cidrs);
        final var first = ClprEndpointAddressPolicy.addressFor("10.0.0.1", PORT, cidrs);
        final var second = ClprEndpointAddressPolicy.addressFor("10.0.0.2", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST)).thenReturn(first.getAddress(), first.getAddress(), second.getAddress());
        final var firstChannel = mock(ManagedChannel.class);
        final var secondChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(first, true)).thenReturn(firstChannel);
            mocked.when(() -> ClprEndpointClientImpl.newChannel(second, true)).thenReturn(secondChannel);
            final var firstClient = cache.clientFor(SOLO_HOST, PORT, true);
            assertThat(cache.clientFor(SOLO_HOST, PORT, true)).isSameAs(firstClient);
            assertThat(cache.clientFor(SOLO_HOST, PORT, true)).isNotSameAs(firstClient);
            verify(firstChannel).shutdown();
            when(clprConfig.requireNumericPeerEndpoints()).thenReturn(true);
            assertThatThrownBy(() -> cache.clientFor(SOLO_HOST, PORT, true)).isInstanceOf(ClientException.class);
            verify(secondChannel).shutdown();
            verify(resolver, times(3)).resolve(SOLO_HOST);
        }
    }

    @Test
    void dnsFailureOrDisallowedAnswerNeverCreatesAChannel() throws Exception {
        when(clprConfig.requireNumericPeerEndpoints()).thenReturn(false);
        when(resolver.resolve(SOLO_HOST))
                .thenThrow(new UnknownHostException(SOLO_HOST))
                .thenReturn(InetAddresses.forString("10.0.0.1"));
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> cache.clientFor(SOLO_HOST, PORT, false))
                    .isInstanceOf(ClientException.class)
                    .hasCauseInstanceOf(UnknownHostException.class);
            assertThatThrownBy(() -> cache.clientFor(SOLO_HOST, PORT, false)).isInstanceOf(ClientException.class);
            mocked.verifyNoInteractions();
        }
    }

    @Test
    void closesCachedChannelWhenItsAdditionalRangeIsRemoved() {
        final var privateHost = "10.0.0.1";
        final var privateAddress = ClprEndpointAddressPolicy.addressFor(privateHost, PORT, Set.of("10.0.0.1/32"));
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of("10.0.0.1/32"));
        final var channel = mock(ManagedChannel.class);
        final var replacementChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(privateAddress, false))
                    .thenReturn(channel, replacementChannel);
            final var first = cache.clientFor(privateHost, PORT, false);
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of());
            assertThatThrownBy(() -> cache.clientFor(privateHost, PORT, false)).isInstanceOf(ClientException.class);
            verify(channel).shutdown();
            verify(channel).shutdownNow();
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(privateAddress, false), times(1));
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of("10.0.0.1/32"));
            assertThat(cache.clientFor(privateHost, PORT, false)).isNotSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(privateAddress, false), times(2));
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeReusingPublicClient() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(channel);
            cache.clientFor(HOST, PORT, false);
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of("10.0.0.0/33"));
            assertThatThrownBy(() -> cache.clientFor(HOST, PORT, false)).isInstanceOf(ClientException.class);
            verify(channel).shutdown();
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false), times(1));
        }
    }

    @Test
    void reusesClientForEquivalentIpv6Literals() {
        final var host = "2001:4860:4860::8888";
        final var address = ClprEndpointAddressPolicy.addressFor(host, PORT, Set.of());
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(address, true)).thenReturn(channel);
            final var first = cache.clientFor(host, PORT, true);
            assertThat(cache.clientFor("2001:4860:4860:0:0:0:0:8888", PORT, true))
                    .isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(address, true), times(1));
        }
    }

    @Test
    void reusesClientForIpv4MappedLiteral() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(channel);
            final var first = cache.clientFor(HOST, PORT, false);
            assertThat(cache.clientFor("::ffff:8.8.8.8", PORT, false)).isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false), times(1));
        }
    }
}
