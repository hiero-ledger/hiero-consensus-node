package org.hiero.otter.fixtures.container.utils;


import com.github.dockerjava.api.model.ContainerNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

public class DockerUtils {

    private DockerUtils() {}

    /**
     * Returns the IP address of a container in a given container network.
     *
     * @param container the container to get the IP address of
     * @param network the network the container is connected to
     * @return the IP address of a container
     */
    public static String getNetworkIpAddress(@NonNull final GenericContainer<?> container, @NonNull final Network network) {
        final Map<String, ContainerNetwork> networks =
                container.getContainerInfo().getNetworkSettings().getNetworks();
        return networks.values().stream()
                .filter(it -> network.getId().equals(it.getNetworkID()))
                .findAny()
                .map(ContainerNetwork::getIpAddress)
                .orElseThrow();
    }

}
