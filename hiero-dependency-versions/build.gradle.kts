/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-kotlin")
}

dependencies {
    api(platform("io.netty:netty-bom:4.1.118.Final"))

    // forward logging from modules using SLF4J (e.g. 'org.hyperledger.besu.evm') to Log4J
    runtime("org.apache.logging.log4j:log4j-slf4j2-impl") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }
}

val autoService = "1.1.1"
val besu = "24.3.3"
val bouncycastle = "1.80"
val dagger = "2.55"
val eclipseCollections = "11.1.0"
val grpc = "1.70.0"
val hederaCryptography = "0.1.1-SNAPSHOT"
val helidon = "4.1.6"
val jackson = "2.18.2"
val junit5 = "5.10.3!!" // no updates beyond 5.10.3 until #17125 is resolved
val log4j = "2.24.3"
val mockito = "5.15.2"
val protobuf = "4.29.3"
val testContainers = "1.20.4"
val tuweni = "2.4.2"
val webcompare = "2.1.7"

dependencies.constraints {
    api("io.helidon.common:helidon-common:$helidon") { because("io.helidon.common") }
    api("io.helidon.webclient:helidon-webclient:$helidon") { because("io.helidon.webclient") }
    api("io.helidon.webclient:helidon-webclient-grpc:$helidon") {
        because("io.helidon.webclient.grpc")
    }
    api("org.awaitility:awaitility:4.2.0") { because("awaitility") }
    api("com.fasterxml.jackson.core:jackson-core:$jackson") {
        because("com.fasterxml.jackson.core")
    }
    api("com.fasterxml.jackson.core:jackson-databind:$jackson") {
        because("com.fasterxml.jackson.databind")
    }
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jackson") {
        because("com.fasterxml.jackson.dataformat.yaml")
    }
    api("com.github.ben-manes.caffeine:caffeine:3.1.8") { because("com.github.benmanes.caffeine") }
    api("com.github.docker-java:docker-java-api:3.4.1") { because("com.github.dockerjava.api") }
    api("com.github.spotbugs:spotbugs-annotations:4.9.1") {
        because("com.github.spotbugs.annotations")
    }
    api("com.google.auto.service:auto-service-annotations:$autoService") {
        because("com.google.auto.service")
    }
    api("com.google.auto.service:auto-service:$autoService") {
        because("com.google.auto.service.processor")
    }
    api("com.google.guava:guava:33.4.0-jre") { because("com.google.common") }
    api("com.google.j2objc:j2objc-annotations:3.0.0") { because("com.google.j2objc.annotations") }
    api("com.google.jimfs:jimfs:1.3.0") { because("com.google.common.jimfs") }
    api("com.google.protobuf:protobuf-java:$protobuf") { because("com.google.protobuf") }
    api("com.google.protobuf:protobuf-java-util:$protobuf") { because("com.google.protobuf.util") }
    api("com.hedera.pbj:pbj-runtime:0.9.2") { because("com.hedera.pbj.runtime") }
    api("com.squareup:javapoet:1.13.0") { because("com.squareup.javapoet") }
    api("net.java.dev.jna:jna:5.12.1") { because("com.sun.jna") }
    api("com.google.dagger:dagger:$dagger") { because("dagger") }
    api("com.google.dagger:dagger-compiler:$dagger") { because("dagger.compiler") }
    api("io.grpc:grpc-netty:$grpc") { because("io.grpc.netty") }
    api("io.grpc:grpc-protobuf:$grpc") { because("io.grpc.protobuf") }
    api("io.grpc:grpc-stub:$grpc") { because("io.grpc.stub") }
    api("com.esaulpaugh:headlong:12.3.3") { because("com.esaulpaugh.headlong") }
    api("info.picocli:picocli:4.7.6") { because("info.picocli") }
    api("io.github.classgraph:classgraph:4.8.179") { because("io.github.classgraph") }
    api("io.perfmark:perfmark-api:0.27.0") { because("io.perfmark") }
    api("io.prometheus:simpleclient:0.16.0") { because("io.prometheus.simpleclient") }
    api("io.prometheus:simpleclient_httpserver:0.16.0") {
        because("io.prometheus.simpleclient.httpserver")
    }
    api("jakarta.inject:jakarta.inject-api:2.0.1") { because("jakarta.inject") }
    api("javax.inject:javax.inject:1") { because("javax.inject") }
    api("com.goterl:lazysodium-java:5.1.4") { because("lazysodium.java") }
    api("net.i2p.crypto:eddsa:0.3.0") { because("net.i2p.crypto.eddsa") }
    api("org.antlr:antlr4-runtime:4.13.2") { because("org.antlr.antlr4.runtime") }
    api("commons-codec:commons-codec:1.17.1") { because("org.apache.commons.codec") }
    api("org.apache.commons:commons-collections4:4.4") {
        because("org.apache.commons.collections4")
    }
    api("commons-io:commons-io:2.18.0") { because("org.apache.commons.io") }
    api("org.apache.commons:commons-lang3:3.17.0") { because("org.apache.commons.lang3") }
    api("org.apache.commons:commons-compress:1.26.0") { because("org.apache.commons.compress") }
    api("org.apache.logging.log4j:log4j-api:$log4j") { because("org.apache.logging.log4j") }
    api("org.apache.logging.log4j:log4j-core:$log4j") { because("org.apache.logging.log4j.core") }
    api("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }
    api("org.assertj:assertj-core:3.27.3") { because("org.assertj.core") }
    api("org.bouncycastle:bcpkix-jdk18on:$bouncycastle") { because("org.bouncycastle.pkix") }
    api("org.bouncycastle:bcprov-jdk18on:$bouncycastle") { because("org.bouncycastle.provider") }
    api("org.eclipse.collections:eclipse-collections-api:$eclipseCollections") {
        because("org.eclipse.collections.api")
    }
    api("org.eclipse.collections:eclipse-collections:$eclipseCollections") {
        because("org.eclipse.collections.impl")
    }
    api("org.hyperledger.besu:besu-datatypes:$besu") { because("org.hyperledger.besu.datatypes") }
    api("org.hyperledger.besu:evm:$besu") { because("org.hyperledger.besu.evm") }
    api("org.hyperledger.besu:secp256k1:0.8.2") {
        because("org.hyperledger.besu.nativelib.secp256k1")
    }
    api("org.jetbrains:annotations:26.0.1") { because("org.jetbrains.annotations") }
    api("org.json:json:20250107") { because("org.json") }
    api("org.junit.jupiter:junit-jupiter-api:$junit5") { because("org.junit.jupiter.api") }
    api("org.junit.jupiter:junit-jupiter-engine:$junit5") { because("org.junit.jupiter.engine") }
    api("org.junit-pioneer:junit-pioneer:2.3.0") { because("org.junitpioneer") }
    api("org.mockito:mockito-core:$mockito") { because("org.mockito") }
    api("org.mockito:mockito-junit-jupiter:$mockito") { because("org.mockito.junit.jupiter") }
    api("org.opentest4j:opentest4j:1.3.0") { because("org.opentest4j") }
    api("org.testcontainers:testcontainers:$testContainers") { because("org.testcontainers") }
    api("org.testcontainers:junit-jupiter:$testContainers") {
        because("org.testcontainers.junit.jupiter")
    }
    api("org.yaml:snakeyaml:2.4") { because("org.yaml.snakeyaml") }
    api("io.tmio:tuweni-bytes:$tuweni") { because("tuweni.bytes") }
    api("io.tmio:tuweni-units:$tuweni") { because("tuweni.units") }
    api("uk.org.webcompere:system-stubs-core:$webcompare") {
        because("uk.org.webcompere.systemstubs.core")
    }
    api("uk.org.webcompere:system-stubs-jupiter:$webcompare") {
        because("uk.org.webcompere.systemstubs.jupiter")
    }
    api("com.google.protobuf:protoc:$protobuf")
    api("io.grpc:protoc-gen-grpc-java:$grpc")

    api("com.hedera.cryptography:hedera-cryptography-pairings-api:$hederaCryptography") {
        because("com.hedera.cryptography.pairings.api")
    }
    api("com.hedera.cryptography:hedera-cryptography-altbn128:$hederaCryptography") {
        because("com.hedera.cryptography.altbn128")
    }
    api("com.hedera.cryptography:hedera-cryptography-bls:$hederaCryptography") {
        because("com.hedera.cryptography.bls")
    }
    api("com.hedera.cryptography:hedera-cryptography-tss:$hederaCryptography") {
        because("com.hedera.cryptography.tss")
    }
}
