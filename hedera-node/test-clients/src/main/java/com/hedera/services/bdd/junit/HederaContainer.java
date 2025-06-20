// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.spec.HapiPropertySource.NODE_RECORD_STREAM_DIR;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.utility.DockerImageName;

/**
 * A Testcontainers container implementation of a Hedera main node. Useful for bringing up a new
 * container as part of a cluster of Hedera main nodes.
 */
public class HederaContainer extends GenericContainer<HederaContainer> {
    public static final int GRPC_PORT = 50211;

    private final int id;
    private Path recordPath;

    public HederaContainer(DockerImageName dockerImageName, int id) {
        super(dockerImageName);
        final String nodeName = "node_" + id;
        this.id = id;
        this.withExposedPorts(GRPC_PORT)
                .withCommand("/bin/sh", "-c", "sleep 2 && ./start-services.sh")
                .waitingFor(new WaitStrategy() {
                    @Override
                    public void waitUntilReady(final WaitStrategyTarget waitStrategyTarget) {
                        // Intentionally empty
                    }

                    @Override
                    public WaitStrategy withStartupTimeout(final Duration duration) {
                        return this;
                    }
                })
                .withEnv("NODE_ID", "" + id)
                .withEnv("CI_WAIT_FOR_PEERS", "true")
                .withNetworkAliases(nodeName)
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName(nodeName));
    }

    /**
     * Specify the directory within the classpath to find the config-mount files
     *
     * @param dir The dir
     * @return this
     */
    public HederaContainer withClasspathResourceMappingDir(String dir) {
        return this.withClasspathResourceMapping(
                        dir + "/config.txt", "/opt/hedera/services/config-mount/config.txt", BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/api-permission.properties",
                        "/opt/hedera/services/config-mount/api-permission.properties",
                        BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/application.properties",
                        "/opt/hedera/services/config-mount/application.properties",
                        BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/bootstrap.properties",
                        "/opt/hedera/services/config-mount/bootstrap.properties",
                        BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/log4j2.xml", "/opt/hedera/services/config-mount/log4j2.xml", BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/node.properties",
                        "/opt/hedera/services/config-mount/node.properties",
                        BindMode.READ_ONLY)
                .withClasspathResourceMapping(
                        dir + "/settings.txt", "/opt/hedera/services/config-mount/settings.txt", BindMode.READ_ONLY);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public HederaContainer withWorkspace(File workspace) {
        final var outputPath = new File(workspace, "output/node_" + id);
        final var savedPath = new File(workspace, "saved/node_" + id);
        outputPath.mkdirs();
        savedPath.mkdirs();
        return this.withFileSystemBind(outputPath.getAbsolutePath(), "/opt/hedera/services/output", BindMode.READ_WRITE)
                .withFileSystemBind(
                        savedPath.getAbsolutePath(), "/opt/hedera/services/data/saved", BindMode.READ_WRITE);
    }

    public HederaContainer withRecordStreamFolderBinding(final File workspace, final String recordStreamFolderName) {
        recordPath =
                Path.of(workspace.getAbsolutePath(), "records", "node_" + id).toAbsolutePath();
        final var recordStreamFolder = new File(recordPath.toString());
        recordStreamFolder.mkdirs();
        return this.withFileSystemBind(
                recordPath.toString(),
                Path.of(
                                File.separator,
                                "opt",
                                "hedera",
                                "META-INF/services",
                                recordStreamFolderName,
                                NODE_RECORD_STREAM_DIR)
                        .toString(),
                BindMode.READ_WRITE);
    }

    public String getRecordPath() {
        return recordPath.toString();
    }

    public boolean isActive() {
        return this.getLogs().contains("Now current platform status = ACTIVE");
    }

    public void waitUntilActive(final Duration timeout) throws TimeoutException {
        final var now = System.currentTimeMillis();
        final var failAfter = now + timeout.toMillis();
        while (!isActive() && System.currentTimeMillis() < failAfter) {
            // Busy Loop
            try {
                MILLISECONDS.sleep(Math.min(100, timeout.toMillis() / 100));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (!isActive()) {
            throw new TimeoutException(String.format("Timed out waiting for node_%d to become active", id));
        }
    }

    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
