// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-peer client caching in {@link ClprEndpointClientCache}: a client (and the channel it
 * owns) is built once and reused across calls, rebuilt only when the pinned peer certificate changes,
 * and torn down on {@link ClprEndpointClientCache#shutdownAll()}. {@code ClprEndpointClientImpl.newChannel}
 * is stubbed so no real network resources are created.
 */
class ClprEndpointClientCacheTest {

    private static final String HOST = "10.0.0.1";
    private static final int PORT = 50211;
    private static final Bytes CERT_A = Bytes.wrap(new byte[] {1, 2, 3});
    private static final Bytes CERT_B = Bytes.wrap(new byte[] {4, 5, 6});
    private final ClprLeafCredentials creds = mock(ClprLeafCredentials.class);

    @Test
    @DisplayName("reuses one client across calls to the same peer with an unchanged certificate")
    void reusesClientForSamePeer() {
        final var cache = new ClprEndpointClientCache();
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()))
                    .thenReturn(channel);

            final var first = cache.clientFor(HOST, PORT, CERT_A, creds);
            final var second = cache.clientFor(HOST, PORT, CERT_A, creds);

            assertThat(second).isSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()), times(1));
        }
    }

    @Test
    @DisplayName("rebuilds the client and tears down the old one when the pinned certificate changes")
    void rebuildsClientOnCertificateChange() {
        final var cache = new ClprEndpointClientCache();
        final var oldChannel = mock(ManagedChannel.class);
        final var newChannel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()))
                    .thenReturn(oldChannel, newChannel);

            final var first = cache.clientFor(HOST, PORT, CERT_A, creds);
            final var second = cache.clientFor(HOST, PORT, CERT_B, creds);

            assertThat(second).isNotSameAs(first);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()), times(2));
            verify(oldChannel).shutdown();
        }
    }

    @Test
    @DisplayName("plaintext calls to the same peer share a client regardless of the certificate argument")
    void plaintextIgnoresCertificateInCacheKey() {
        final var cache = new ClprEndpointClientCache();
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()))
                    .thenReturn(channel);

            // Plaintext path: leaf credentials are null, so the certificate must not affect the cache key.
            cache.clientFor(HOST, PORT, CERT_A, null);
            cache.clientFor(HOST, PORT, null, null);

            mocked.verify(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()), times(1));
        }
    }

    @Test
    @DisplayName("shutdownAll tears down every cached client's channel and clears the cache")
    void shutdownAllTearsDownChannels() {
        final var cache = new ClprEndpointClientCache();
        final var channelA = mock(ManagedChannel.class);
        final var channelB = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(HOST), anyInt(), any(), any()))
                    .thenReturn(channelA, channelB);

            cache.clientFor(HOST, PORT, null, null);
            cache.clientFor(HOST, PORT + 1, null, null);

            cache.shutdownAll();

            verify(channelA).shutdown();
            verify(channelB).shutdown();

            // Cache is cleared: a subsequent call rebuilds rather than reusing a shut-down client.
            cache.clientFor(HOST, PORT, null, null);
            mocked.verify(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()), times(2));
        }
    }

    @Test
    @DisplayName("shutdownAll on a cache with no entries does not throw")
    void shutdownAllOnEmptyCacheDoesNotThrow() {
        final var cache = new ClprEndpointClientCache();
        assertThatNoException().isThrownBy(cache::shutdownAll);
    }

    @Test
    @DisplayName("calling shutdownAll twice only shuts the channel down once")
    void shutdownAllIsIdempotent() {
        final var cache = new ClprEndpointClientCache();
        final var channel = mock(ManagedChannel.class);
        try (final var mocked = mockStatic(ClprEndpointClientImpl.class)) {
            mocked.when(() -> ClprEndpointClientImpl.newChannel(eq(HOST), eq(PORT), any(), any()))
                    .thenReturn(channel);

            cache.clientFor(HOST, PORT, null, null);

            cache.shutdownAll();
            verify(channel, times(1)).shutdown();

            // Cache is already cleared, so a second call has nothing left to tear down.
            cache.shutdownAll();
            verify(channel, times(1)).shutdown();
        }
    }
}
