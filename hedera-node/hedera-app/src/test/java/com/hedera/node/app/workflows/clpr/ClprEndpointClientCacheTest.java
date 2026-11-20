// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.net.InetAddresses;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
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
    private ClprEndpointClientCache cache;

    @BeforeEach
    void setUp() {
        when(configProvider.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(ClprConfig.class)).thenReturn(clprConfig);
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of());
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(false);
        when(clprConfig.maxConcurrentSyncs()).thenReturn(4);
        cache = new ClprEndpointClientCache(configProvider, resolver);
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    void reusesClientWhenTransportIsUnchanged() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(ADDRESS), anyBoolean()))
                    .thenReturn(channel);
            final var first = clientFor(HOST, PORT, true);
            assertThat(clientFor(HOST, PORT, true)).isSameAs(first);
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
            final var first = clientFor(HOST, PORT, false);
            assertThat(clientFor(HOST, PORT, true)).isNotSameAs(first);
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
            clientFor(HOST, PORT, false);
            clientFor(HOST, PORT + 1, false);
            cache.shutdownAll();
            verify(channelA).shutdown();
            verify(channelB).shutdown();
            clientFor(HOST, PORT, false);
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
            clientFor(HOST, PORT, false);
            cache.shutdownAll();
            cache.shutdownAll();
            verify(channel, times(1)).shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "peer.example", "127.0.0.1", "169.254.169.254", "10.0.0.1", "::1", "fd00::1"})
    void rejectsUnpermittedEndpointBeforeCreatingChannel(final String host) {
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> clientFor(host, PORT, false)).isInstanceOf(ClientException.class);
            mocked.verifyNoInteractions();
            verifyNoInteractions(resolver);
        }
    }

    @Test
    void dnsPeersUseValidatedNumericAddressesAndReplaceChannelsWhenTheAddressChanges() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        final var cidrs = Set.of("10.0.0.0/24");
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(cidrs);
        final var first = ClprEndpointAddressPolicy.addressFor("10.0.0.1", PORT, cidrs);
        final var second = ClprEndpointAddressPolicy.addressFor("10.0.0.2", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(first), answers(first), answers(second));
        final var firstChannel = mock(ManagedChannel.class);
        final var secondChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(first, true)).thenReturn(firstChannel);
            mocked.when(() -> ClprEndpointClientImpl.newChannel(second, true)).thenReturn(secondChannel);
            final var firstClient = clientFor(SOLO_HOST, PORT, true);
            assertThat(clientFor(SOLO_HOST, PORT, true)).isSameAs(firstClient);
            assertThat(clientFor(SOLO_HOST, PORT, true)).isNotSameAs(firstClient);
            verify(firstChannel).shutdown();
            when(clprConfig.allowDnsPeerEndpoints()).thenReturn(false);
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, true))
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("clpr.allowDnsPeerEndpoints");
            verify(secondChannel).shutdown();
            verify(resolver, times(3)).resolve(SOLO_HOST);
        }
    }

    @Test
    void dnsFailureOrDisallowedAnswerNeverCreatesAChannel() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        when(resolver.resolve(SOLO_HOST))
                .thenThrow(new UnknownHostException(SOLO_HOST))
                .thenReturn(new InetAddress[] {InetAddresses.forString("10.0.0.1")});
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false))
                    .isInstanceOf(ClientException.class)
                    .hasCauseInstanceOf(UnknownHostException.class);
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false)).isInstanceOf(ClientException.class);
            mocked.verifyNoInteractions();
        }
    }

    @Test
    void dnsPeersKeepTheirAddressWhileARotatingAnswerStillContainsIt() throws Exception {
        final var cidrs = allowDnsWith("10.0.0.0/24");
        final var first = ClprEndpointAddressPolicy.addressFor("10.0.0.1", PORT, cidrs);
        final var second = ClprEndpointAddressPolicy.addressFor("10.0.0.2", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(first, second), answers(second, first));
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(first, false)).thenReturn(mock(ManagedChannel.class));
            final var client = clientFor(SOLO_HOST, PORT, false);
            assertThat(clientFor(SOLO_HOST, PORT, false)).isSameAs(client);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(second, false), never());
        }
    }

    @Test
    void dnsPeersSkipRecordsOutsideThePermittedRanges() throws Exception {
        final var cidrs = allowDnsWith("fd00::/64");
        final var permitted = ClprEndpointAddressPolicy.addressFor("fd00::1", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST))
                .thenReturn(new InetAddress[] {InetAddresses.forString("192.168.1.5"), permitted.getAddress()});
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(permitted, false))
                    .thenReturn(mock(ManagedChannel.class));
            clientFor(SOLO_HOST, PORT, false);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(permitted, false), times(1));
        }
    }

    @Test
    void dnsFailureOrRejectedAnswerEvictsTheCachedClient() throws Exception {
        final var cidrs = allowDnsWith("10.0.0.0/24");
        final var address = ClprEndpointAddressPolicy.addressFor("10.0.0.1", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST))
                .thenReturn(answers(address))
                .thenThrow(new UnknownHostException(SOLO_HOST))
                .thenReturn(answers(address))
                .thenReturn(new InetAddress[] {InetAddresses.forString("192.168.1.5")})
                .thenReturn(answers(address));
        final var firstChannel = mock(ManagedChannel.class);
        final var secondChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(address, false))
                    .thenReturn(firstChannel, secondChannel, mock(ManagedChannel.class));
            final var first = clientFor(SOLO_HOST, PORT, false);
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false)).isInstanceOf(ClientException.class);
            verify(firstChannel).shutdown();
            final var second = clientFor(SOLO_HOST, PORT, false);
            assertThat(second).isNotSameAs(first);
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false)).isInstanceOf(ClientException.class);
            verify(secondChannel).shutdown();
            assertThat(clientFor(SOLO_HOST, PORT, false)).isNotSameAs(second);
        }
    }

    @Test
    void dnsPeersRebuildWhenTheTransportChanges() throws Exception {
        final var cidrs = allowDnsWith("10.0.0.0/24");
        final var address = ClprEndpointAddressPolicy.addressFor("10.0.0.1", PORT, cidrs);
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(address));
        final var plaintext = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(address, false)).thenReturn(plaintext);
            mocked.when(() -> ClprEndpointClientImpl.newChannel(address, true)).thenReturn(mock(ManagedChannel.class));
            final var first = clientFor(SOLO_HOST, PORT, false);
            assertThat(clientFor(SOLO_HOST, PORT, true)).isNotSameAs(first);
            verify(plaintext).shutdown();
            verify(plaintext, never()).shutdownNow();
        }
    }

    @Test
    void numericPeersNeverUseTheResolverWhenDnsIsAllowed() {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(mock(ManagedChannel.class));
            clientFor(HOST, PORT, false);
            clientFor("::ffff:8.8.8.8", PORT, false);
            verifyNoInteractions(resolver);
        }
    }

    @Test
    void invalidHostsAndPortsNeverReachDns() {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        assertThatThrownBy(() -> clientFor("peer..svc", PORT, false)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> clientFor(SOLO_HOST, 0, false)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> clientFor(SOLO_HOST, 65536, false)).isInstanceOf(ClientException.class);
        verifyNoInteractions(resolver);
    }

    @Test
    void disablingDnsDuringLookupDiscardsTheAnswer() throws Exception {
        final var enabled = new AtomicBoolean(true);
        when(clprConfig.allowDnsPeerEndpoints()).thenAnswer(invocation -> enabled.get());
        when(resolver.resolve(SOLO_HOST)).thenAnswer(invocation -> {
            enabled.set(false);
            return answers(ADDRESS);
        });
        try (final var transport = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false)).isInstanceOf(ClientException.class);
            transport.verifyNoInteractions();
        }
    }

    @Test
    void expiredSyncCannotInstallAUsableDnsAnswer() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        final var deadline = mock(Deadline.class);
        when(deadline.isExpired()).thenReturn(false, true);
        when(deadline.timeRemaining(TimeUnit.NANOSECONDS)).thenReturn(TimeUnit.SECONDS.toNanos(10));
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(ADDRESS));
        try (final var transport = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(() -> cache.clientFor(SOLO_HOST, PORT, false, deadline))
                    .isInstanceOf(ClientException.class);
            verify(resolver).resolve(SOLO_HOST);
            transport.verifyNoInteractions();
        }
    }

    @Test
    void trailingDotSharesTheClientButPreservesTheResolverQuery() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(ADDRESS));
        when(resolver.resolve(SOLO_HOST + ".")).thenReturn(answers(ADDRESS));
        try (final var transport = mockStatic(ClprEndpointClientImpl.class)) {
            transport
                    .when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false))
                    .thenReturn(mock(ManagedChannel.class));
            final var first = clientFor(SOLO_HOST, PORT, false);
            assertThat(clientFor(SOLO_HOST + ".", PORT, false)).isSameAs(first);
            verify(resolver).resolve(SOLO_HOST + ".");
        }
    }

    @Test
    void blockedDnsLeavesNumericClientsAvailableAndLateAnswerCannotReopenClosedCache() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        final int dnsSlots = clprConfig.maxConcurrentSyncs() - 1;
        final var entered = new CountDownLatch(dnsSlots);
        final var release = new CountDownLatch(1);
        when(resolver.resolve(SOLO_HOST)).thenAnswer(invocation -> {
            entered.countDown();
            // The native resolver can complete successfully even after cancellation.
            awaitUninterruptibly(release);
            return answers(ADDRESS);
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var transport = mockStatic(ClprEndpointClientImpl.class)) {
            transport
                    .when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false))
                    .thenReturn(mock(ManagedChannel.class));
            final var numeric = clientFor(HOST, PORT, false);
            final var lookups = IntStream.range(0, dnsSlots)
                    .mapToObj(i -> workers.submit(() -> clientFor(SOLO_HOST, PORT, false)))
                    .toList();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(workers.submit(() -> clientFor(HOST, PORT, false)).get(2, TimeUnit.SECONDS))
                        .isSameAs(numeric);
                cache.close();
            } finally {
                release.countDown();
            }
            for (final var lookup : lookups) {
                assertThatThrownBy(() -> lookup.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(ClientException.class);
            }
            assertThatThrownBy(() -> clientFor(HOST, PORT, false)).isInstanceOf(ClientException.class);
        }
    }

    @Test
    void rotationInstallsReplacementBeforeRetiringOldTransportOutsideCacheLock() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        final var rotated = ClprEndpointAddressPolicy.addressFor("8.8.4.4", PORT, Set.of());
        when(resolver.resolve(SOLO_HOST)).thenReturn(answers(ADDRESS), answers(rotated), answers(rotated));
        final var oldChannel = mock(ManagedChannel.class);
        final var replacement = mock(ManagedChannel.class);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor();
                var transport = mockStatic(ClprEndpointClientImpl.class)) {
            transport
                    .when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false))
                    .thenReturn(oldChannel);
            transport
                    .when(() -> ClprEndpointClientImpl.newChannel(rotated, false))
                    .thenReturn(replacement);
            final var oldClient = clientFor(SOLO_HOST, PORT, false);
            doAnswer(invocation -> {
                        final var duringRetirement = worker.submit(() -> clientFor(SOLO_HOST, PORT, false));
                        assertThat(duringRetirement.get(2, TimeUnit.SECONDS)).isNotSameAs(oldClient);
                        return oldChannel;
                    })
                    .when(oldChannel)
                    .shutdown();
            assertThat(clientFor(SOLO_HOST, PORT, false)).isNotSameAs(oldClient);
            verify(oldChannel).shutdown();
            verify(oldChannel, never()).shutdownNow();
        }
    }

    @Test
    void dnsNamesExplainHowToEnableDnsWhenItIsDisabled() {
        assertThatThrownBy(() -> clientFor(SOLO_HOST, PORT, false))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("clpr.allowDnsPeerEndpoints=true");
        verifyNoInteractions(resolver);
    }

    @Test
    void dnsLookupIsBoundedBySyncTimeout() throws Exception {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        final var release = new CountDownLatch(1);
        when(resolver.resolve(SOLO_HOST)).thenAnswer(invocation -> {
            release.await();
            return new InetAddress[0];
        });
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            assertThatThrownBy(
                            () -> cache.clientFor(SOLO_HOST, PORT, false, Deadline.after(250, TimeUnit.MILLISECONDS)))
                    .isInstanceOf(ClientException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
            mocked.verifyNoInteractions();
        } finally {
            release.countDown();
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
            final var first = clientFor(privateHost, PORT, false);
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of());
            assertThatThrownBy(() -> clientFor(privateHost, PORT, false)).isInstanceOf(ClientException.class);
            verify(channel).shutdown();
            verify(channel).shutdownNow();
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(privateAddress, false), times(1));
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of("10.0.0.1/32"));
            assertThat(clientFor(privateHost, PORT, false)).isNotSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(privateAddress, false), times(2));
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeReusingPublicClient() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(channel);
            clientFor(HOST, PORT, false);
            when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of("10.0.0.0/33"));
            assertThatThrownBy(() -> clientFor(HOST, PORT, false)).isInstanceOf(ClientException.class);
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
            final var first = clientFor(host, PORT, true);
            assertThat(clientFor("2001:4860:4860:0:0:0:0:8888", PORT, true)).isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(address, true), times(1));
        }
    }

    @Test
    void reusesClientForIpv4MappedLiteral() {
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false)).thenReturn(channel);
            final var first = clientFor(HOST, PORT, false);
            assertThat(clientFor("::ffff:8.8.8.8", PORT, false)).isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(ADDRESS, false), times(1));
        }
    }

    private ClprEndpointClient clientFor(final String host, final int port, final boolean useTls) {
        return cache.clientFor(host, port, useTls, Deadline.after(10, TimeUnit.SECONDS));
    }

    private Set<String> allowDnsWith(final String cidr) {
        when(clprConfig.allowDnsPeerEndpoints()).thenReturn(true);
        when(clprConfig.additionalPeerEndpointCidrs()).thenReturn(Set.of(cidr));
        return Set.of(cidr);
    }

    private static InetAddress[] answers(final InetSocketAddress... addresses) {
        final var answers = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            answers[i] = addresses[i].getAddress();
        }
        return answers;
    }
}
