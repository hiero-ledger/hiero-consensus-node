// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for sockets
 *
 * @param ipTos                      The IP_TOS to set for a socket, from 0 to 255, or -1 to not set one. This number
 *                                   (if not -1) will be part of every TCP/IP packet, and is normally ignored by
 *                                   internet routers, but it is possible to make routers change their handling of
 *                                   packets based on this number, such as for providing different Quality of Service
 *                                   (QoS). <a href="https://en.wikipedia.org/wiki/Type_of_service">Type of Service</a>
 * @param bufferSize                 for BufferedInputStream and BufferedOutputStream for syncing
 * @param timeoutSyncClientSocket    timeout when waiting for data
 * @param timeoutSyncClientConnect   timeout when establishing a connection
 * @param timeoutServerAcceptConnect timeout when server is waiting for another member to create a connection
 * @param useLoopbackIp              should be set to true when using the internet simulator
 * @param tcpNoDelay                 if true, then Nagel's algorithm is disabled, which helps latency, hurts bandwidth
 *                                   usage
 * @param gzipCompression            whether to use gzip compression over the network
 * @param waitBetweenConnectionRetries      how many ms should we wait before trying to establish new connection after previous
 *                                   one is broken, to avoid spam on broken cert; zero or negative for no-sleep
 * @param maxSocketAcceptThreads     maximum amount of threads which will be spawned to handle incoming SSL socket
 *                                   accepts, needed because of length SSL handshake; at same time, we don't want it to
 *                                   be unlimited, to not run out of threads on some kind of DOS
 */
@ConfigData("socket")
public record SocketConfig(
        @ConfigProperty(defaultValue = "-1") int ipTos,
        @ConfigProperty(defaultValue = "8192") int bufferSize,
        @ConfigProperty(defaultValue = "5000") int timeoutSyncClientSocket,
        @ConfigProperty(defaultValue = "5000") int timeoutSyncClientConnect,
        @ConfigProperty(defaultValue = "5000") int timeoutServerAcceptConnect,
        @ConfigProperty(defaultValue = "false") boolean useLoopbackIp,
        @ConfigProperty(defaultValue = "true") boolean tcpNoDelay,
        @ConfigProperty(defaultValue = "false") boolean gzipCompression,
        @ConfigProperty(defaultValue = "10") int waitBetweenConnectionRetries,
        @ConfigProperty(defaultValue = "30") int maxSocketAcceptThreads) {}
