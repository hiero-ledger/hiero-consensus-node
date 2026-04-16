# Observability stack for Swirlds Benchmarks

This folder contains a Docker Compose–based observability stack for monitoring and logging Swirlds Benchmarks:

| Service    | Purpose                                   | Default URL                     |
|------------|-------------------------------------------|---------------------------------|
| Prometheus | Metrics scraping & storage                | <http://localhost:9090>         |
| Loki       | Log aggregation                           | <http://localhost:3100>         |
| Alloy      | Log collector (ships logs → Loki)         | <http://localhost:12345> (UI)   |
| Grafana    | Dashboards                                | <http://localhost:3001>         |

## Configuration

All main parameters are in **`.env`** in this directory.  
Edit the file before starting the stack to change image versions, ports, or paths:

```dotenv
LOGS_PATH=../output          # path to benchmark log files (relative to this dir)
PROMETHEUS_PORT=9090
GRAFANA_PORT=3001
LOKI_PORT=3100
ALLOY_UI_PORT=12345
PROMETHEUS_TARGET_0=host.docker.internal:9999
PROMETHEUS_TARGET_1=host.docker.internal:8888
SCRAPE_INTERVAL=3s
```

## Usage

1. Make sure Docker is installed.
2. Build the JMH shadow JAR:
   ```bash
   ./gradlew :swirlds-benchmarks:jmhJarWithMergedServiceFiles
   ```
3. Start the stack:
   ```bash
   docker compose up -d
   ```
4. Run the desired benchmark.
5. Open Grafana at <http://localhost:3001>.
6. (Optional) Inspect Alloy's pipeline at <http://localhost:12345>.
7. Stop the stack when done:
   ```bash
   docker compose down
   ```

> **Note:** Loki data is stored in a named Docker volume (`loki-data`) and persists across `docker compose down` restarts.  
> To fully reset logs, run `docker compose down -v`.
