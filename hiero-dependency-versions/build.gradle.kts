// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-kotlin")
}

dependencies {
    api(platform("io.netty:netty-bom:4.2.4.Final"))

    // forward logging from modules using SLF4J (e.g. 'org.hyperledger.besu.evm') to Log4J
    runtime("org.apache.logging.log4j:log4j-slf4j2-impl") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }
}

val autoService = "1.1.1"
val besu = "24.3.3"
val bouncycastle = "1.81"
val dagger = "2.56.2"
val eclipseCollections = "13.0.0"
val grpc = "1.72.0"
val hederaCryptography = "2.0.2"
val helidon = "4.2.3"
val jackson = "2.19.0"
val junit5 = "5.10.3!!" // no updates beyond 5.10.3 until #17125 is resolved
val log4j = "2.25.0"
val mockito = "5.18.0"
val pbj = "0.11.15" // ATTENTION: keep in sync with plugin version in 'hapi/hapi/build.gradle.kts'
val protobuf = "4.31.1"
val testContainers = "1.21.3"
val tuweni = "2.4.2"
val webcompare = "2.1.8"

dependencies.constraints {
    api("io.helidon.common:helidon-common:$helidon") { because("io.helidon.common") }
    api("io.helidon.webclient:helidon-webclient:$helidon") { because("io.helidon.webclient") }
    api("io.helidon.webclient:helidon-webclient-grpc:$helidon") {
        because("io.helidon.webclient.grpc")
    }
    api("org.awaitility:awaitility:4.3.0") { because("awaitility") }
    api("com.fasterxml.jackson.core:jackson-core:$jackson") {
        because("com.fasterxml.jackson.core")
    }
    api("com.fasterxml.jackson.core:jackson-databind:$jackson") {
        because("com.fasterxml.jackson.databind")
    }
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jackson") {
        because("com.fasterxml.jackson.dataformat.yaml")
    }
    api("com.github.ben-manes.caffeine:caffeine:3.2.0") { because("com.github.benmanes.caffeine") }
    api("com.github.docker-java:docker-java-api:3.5.3") { because("com.github.dockerjava.api") }
    api("com.github.spotbugs:spotbugs-annotations:4.9.3") {
        because("com.github.spotbugs.annotations")
    }
    api("com.google.auto.service:auto-service-annotations:$autoService") {
        because("com.google.auto.service")
    }
    api("com.google.auto.service:auto-service:$autoService") {
        because("com.google.auto.service.processor")
    }
    api("com.google.guava:guava:33.4.8-jre") { because("com.google.common") }
    api("com.google.j2objc:j2objc-annotations:3.0.0") { because("com.google.j2objc.annotations") }
    api("com.google.jimfs:jimfs:1.3.0") { because("com.google.common.jimfs") }
    api("com.google.protobuf:protobuf-java:$protobuf") { because("com.google.protobuf") }
    api("com.google.protobuf:protobuf-java-util:$protobuf") { because("com.google.protobuf.util") }
    api("com.hedera.pbj:pbj-runtime:$pbj") { because("com.hedera.pbj.runtime") }
    api("com.squareup:javapoet:1.13.0") { because("com.squareup.javapoet") }
    api("net.java.dev.jna:jna:5.17.0") { because("com.sun.jna") }
    api("com.google.dagger:dagger:$dagger") { because("dagger") }
    api("com.google.dagger:dagger-compiler:$dagger") { because("dagger.compiler") }
    api("io.grpc:grpc-netty:$grpc") { because("io.grpc.netty") }
    api("io.grpc:grpc-protobuf:$grpc") { because("io.grpc.protobuf") }
    api("io.grpc:grpc-stub:$grpc") { because("io.grpc.stub") }
    api("io.grpc:grpc-netty-shaded:$grpc") { because("io.grpc.netty.shaded") }
    api("com.esaulpaugh:headlong:13.3.0") { because("com.esaulpaugh.headlong") }
    api("info.picocli:picocli:4.7.7") { because("info.picocli") }
    api("io.github.classgraph:classgraph:4.8.179") { because("io.github.classgraph") }
    api("io.perfmark:perfmark-api:0.27.0") { because("io.perfmark") }
    api("io.prometheus:simpleclient:0.16.0") { because("simpleclient") }
    api("io.prometheus:simpleclient_httpserver:0.16.0") { because("simpleclient.httpserver") }
    api("jakarta.inject:jakarta.inject-api:2.0.1") { because("jakarta.inject") }
    api("javax.inject:javax.inject:1") { because("javax.inject") }
    api("com.goterl:lazysodium-java:5.2.0") { because("com.goterl.lazysodium") }
    api("net.i2p.crypto:eddsa:0.3.0") { because("net.i2p.crypto.eddsa") }
    api("org.antlr:antlr4-runtime:4.13.2") { because("org.antlr.antlr4.runtime") }
    api("commons-codec:commons-codec:1.18.0") { because("org.apache.commons.codec") }
    api("org.apache.commons:commons-collections4:4.5.0") {
        because("org.apache.commons.collections4")
    }
    api("commons-io:commons-io:2.19.0") { because("org.apache.commons.io") }
    api("org.apache.commons:commons-lang3:3.18.0") { because("org.apache.commons.lang3") }
    api("org.apache.commons:commons-compress:1.27.1") { because("org.apache.commons.compress") }
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
    api("org.jetbrains:annotations:26.0.2") { because("org.jetbrains.annotations") }
    api("org.json:json:20250517") { because("org.json") }
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

    api("com.hedera.cryptography:hedera-cryptography-blskeygen:$hederaCryptography") {
        because("com.hedera.cryptography.blskeygen")
    }
    api("com.hedera.cryptography:hedera-cryptography-bls:$hederaCryptography") {
        because("com.hedera.cryptography.bls")
    }
    api("com.hedera.cryptography:hedera-cryptography-pairings-api:$hederaCryptography") {
        because("com.hedera.cryptography.pairings.api")
    }
    api("com.hedera.cryptography:hedera-cryptography-tss:$hederaCryptography") {
        because("com.hedera.cryptography.tss")
    }
    api("com.hedera.cryptography:hedera-cryptography-utils:$hederaCryptography") {
        because("com.hedera.cryptography.utils")
    }
    api("com.hedera.cryptography:hedera-cryptography-rpm:$hederaCryptography") {
        because("com.hedera.cryptography.rpm")
    }
    api("com.hedera.cryptography:hedera-cryptography-hinTS:$hederaCryptography") {
        because("com.hedera.cryptography.hints")
    }
}
