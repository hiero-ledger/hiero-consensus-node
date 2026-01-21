#!/bin/sh
./gradlew -x :test-clients:processResources -x :test-clients:javadoc -x :test-clients:compileJava -x :yahcli:yahCliJar -x :yahcli:compileJava -x :yahcli:compileTestJava -x :yahcli:javadoc -x :hapi:javadoc -x :hapi:generatePbjSource -x :hapi:generateProto -x :hapi:javadocJar -x :hapi:compileJava -x :app-service-contract-impl:javadoc -x :app:javadoc -x :PlatformTestingTool:extractIncludeTimingSensitiveProto -x :hedera-state-validator:

# Run latest failing test
#  clear; time UseBonnevilleHEVM=1 ./gradlew :test-clients:testRepeatable --rerun --tests AssociatePrecompileV2SecurityModelSuite.V2Security041TokenAssociateFromStaticcallAndCallcode
