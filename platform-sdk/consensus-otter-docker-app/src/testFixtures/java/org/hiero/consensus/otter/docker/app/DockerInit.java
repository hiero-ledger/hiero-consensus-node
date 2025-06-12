// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.otter.docker.app.netty.NettyRestServer;
import org.hiero.consensus.otter.docker.app.platform.DockerApp;

public class DockerInit {

    private final static Logger LOGGER = LogManager.getLogger(DockerInit.class);

    private final NettyRestServer server;
    private DockerApp app;

    private DockerInit() throws Exception {
         server = new NettyRestServer(8080);

        // POST /hello
        server.addPost("/hello", (req, body) -> {
            try {
                Map<?, ?> json = new ObjectMapper().readValue(body, Map.class);
                if (json.containsKey("name")) {
                    final String name = json.get("name").toString();
                    return Map.of("answer", "Hello " + name + "!");
                }
                return Map.of("answer", "Hello World!");
            } catch (Exception e) {
                return Map.of("error", "Invalid JSON");
            }
        });

        server.addGet("/create-node", req -> {
            try {
                app = new DockerApp();
                return Map.of("platform", "created");
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        });

        server.addGet("/start-node", req -> {
            if(app != null) {
                app.get().start();
                return Map.of("platform", "started");
            }
            return Map.of("error", "platform not created");
        });
    }

    public void startWebserver() throws InterruptedException {
        server.start();
    }

    public static void main(String[] args) throws Exception {

        new DockerInit().startWebserver();
    }

}
