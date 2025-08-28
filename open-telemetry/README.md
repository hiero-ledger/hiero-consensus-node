# TODO - Some documentation.


### How ro run HAPI tests with OpenTelemetry 

1. Clean HAPI test output directory, if you want fresh test without previously recorded telemetry (optional)  
    `./gradlew :test-clients:cleanData`
2. Clean debug output, Tempo and Prometheus Docker data, if you need clean databases (optional)  
    `./gradlew :open-telemetry:cleanData`
3. Run HAPI test generating some load (duration and TPS can be modified in the test code)  
   `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.UmbrellaRedux.umbrellaRedux"`
4. Start Docker containers, if not yet running  
   `./gradlew :open-telemetry:startDockerContainers`
5. Run Open Telemetry converter to process HAPI test results and export to Tempo and Prometheus   
   `./gradlew -Dtracing.converter.jfrDirectory=./../hedera-node/test-clients/build/hapi-test -Dtracing.converter.blockStreamsDirectory=./../hedera-node/test-clients/build/hapi-test/node0/data/blockStreams :open-telemetry:run`
6. Open Grafana dashboard at http://localhost:3000/explore and explore Spans and Metrics  
7. Stop Docker containers, if needed  
   `./gradlew :open-telemetry:stopDockerContainers`