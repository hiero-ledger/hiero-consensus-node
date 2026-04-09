This folder contains docker images to run Prometheus, Loki, Promtail, and Grafana for monitoring and logging Swirlds Benchmarks.

Before running benchmarks:
1. Make sure you have Docker installed on your machine.
2. Build JMH shadow JAR with merged service files by running:
   ```bash
   ./gradlew :swirlds-benchmarks:jmhJarWithMergedServiceFiles
   ```
3. Run docker services.
    ```bash
    docker-compose up -d
    ```
4. Run desired test
5. Access Grafana at http://localhost:3001
6. Stop docker services after tests are done.
    ```bash
    docker-compose down
    ```