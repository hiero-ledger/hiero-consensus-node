// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
class ConfigProviderImplTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testInitialConfig(final boolean isGenesis) {
        // given
        final var configProvider = new ConfigProviderImpl(isGenesis);

        // when
        final var configuration = configProvider.getConfiguration();

        // then
        assertThat(configuration).isNotNull();
        assertThat(configuration.getVersion()).isZero();
    }

    @Test
    void testApplicationPropertiesLoaded() {
        // given
        final var configProvider = new ConfigProviderImpl(false);

        // when
        final var configuration = configProvider.getConfiguration();
        final String value = configuration.getValue("foo.test");

        // then
        assertThat(value).isEqualTo("123");
    }

    @Test
    void testGenesisNotAlwaysUsed() {
        // given
        final var configProvider = new ConfigProviderImpl(false);

        // when
        final var configuration = configProvider.getConfiguration();
        final String value = configuration.getValue("bar.test");

        // then
        assertThat(value).isEqualTo("456");
    }

    @Test
    void testGenesisOverwritesApplication() {
        // given
        final var configProvider = new ConfigProviderImpl(true);

        // when
        final var configuration = configProvider.getConfiguration();
        final String value = configuration.getValue("bar.test");

        // then
        assertThat(value).isEqualTo("genesis");
    }

    @Test
    void testDifferentApplicationPropertiesFile(final EnvironmentVariables environment) {
        // given
        environment.set(ConfigProviderImpl.APPLICATION_PROPERTIES_PATH_ENV, "for-test/application.properties.test");
        final var configProvider = new ConfigProviderImpl(false);

        // when
        final var configuration = configProvider.getConfiguration();
        final String bar = configuration.getValue("bar.test");

        // then
        assertThat(bar).isEqualTo("456Test");
    }

    @Test
    void testDifferentGenesisPropertiesFile(final EnvironmentVariables environment) {
        // given
        environment.set(ConfigProviderImpl.GENESIS_PROPERTIES_PATH_ENV, "for-test/genesis.properties.test");
        final var configProvider = new ConfigProviderImpl(true);

        // when
        final var configuration = configProvider.getConfiguration();
        final String bar = configuration.getValue("bar.test");

        // then
        assertThat(bar).isEqualTo("genesisTest");
    }

    @Test
    void testApplicationPropertiesFileIsOptional(final EnvironmentVariables environment) {
        // given
        environment.set(ConfigProviderImpl.APPLICATION_PROPERTIES_PATH_ENV, "does-not-exist");
        final var configProvider = new ConfigProviderImpl(true);

        // when
        final var configuration = configProvider.getConfiguration();
        final String bar = configuration.getValue("bar.test");

        // then
        assertThat(configuration.exists("foo.test")).isFalse();
        assertThat(bar).isEqualTo("genesis");
    }

    @Test
    void testGenesisPropertiesFileIsOptional(final EnvironmentVariables environment) {
        // given
        environment.set(ConfigProviderImpl.GENESIS_PROPERTIES_PATH_ENV, "does-not-exist");
        final var configProvider = new ConfigProviderImpl(true);

        // when
        final var configuration = configProvider.getConfiguration();
        final String bar = configuration.getValue("bar.test");

        // then
        assertThat(bar).isEqualTo("456");
    }

    @Test
    void testUpdateDoesUseApplicationProperties() {
        // given
        final var configProvider = new ConfigProviderImpl(false);

        // when
        configProvider.update(Bytes.EMPTY, Bytes.EMPTY);
        final VersionedConfiguration configuration = configProvider.getConfiguration();
        final String value1 = configuration.getValue("foo.test");
        final String value2 = configuration.getValue("bar.test");

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(value1).isEqualTo("123");
        assertThat(value2).isEqualTo("456");
    }

    @Test
    void testUpdateDoesNotUseGenesisProperties() {
        // given
        final var configProvider = new ConfigProviderImpl(true);

        // when
        configProvider.update(Bytes.EMPTY, Bytes.EMPTY);
        final VersionedConfiguration configuration = configProvider.getConfiguration();

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(configuration.getValue("bar.test")).isNotEqualTo("genesis");
    }

    @Test
    void incorporatesOverridePropertiesEvenAfterUpdate() {
        final var subject = new ConfigProviderImpl(false, null, Map.of("baz.test", "789"));
        final var config = subject.getConfiguration();
        assertThat(config.getValue("baz.test")).isEqualTo("789");

        subject.update(Bytes.EMPTY, Bytes.EMPTY);
        final var postUpdateConfig = subject.getConfiguration();
        assertThat(postUpdateConfig.getValue("baz.test")).isEqualTo("789");
    }

    @Test
    void testUpdateProvidesConfigProperty() {
        // given
        final var configProvider = new ConfigProviderImpl(true);
        final ServicesConfigurationList servicesConfigurationList = ServicesConfigurationList.newBuilder()
                .nameValue(Setting.newBuilder().name("update.test").value("789").build())
                .build();
        final Bytes bytes = ServicesConfigurationList.PROTOBUF.toBytes(servicesConfigurationList);

        // when
        configProvider.update(bytes, Bytes.EMPTY);
        final VersionedConfiguration configuration = configProvider.getConfiguration();
        final String value = configuration.getValue("update.test");

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(value).isEqualTo("789");
    }

    @Test
    void testUpdateProvidesConfigProperty2() {
        // given
        final var configProvider = new ConfigProviderImpl(true);
        final ServicesConfigurationList servicesConfigurationList = ServicesConfigurationList.newBuilder()
                .nameValue(Setting.newBuilder().name("update.test").value("789").build())
                .build();
        final Bytes bytes = ServicesConfigurationList.PROTOBUF.toBytes(servicesConfigurationList);

        // when
        configProvider.update(Bytes.EMPTY, bytes);
        final VersionedConfiguration configuration = configProvider.getConfiguration();
        final String value = configuration.getValue("update.test");

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(value).isEqualTo("789");
    }

    @Test
    void testUpdateProvidesConfigProperties() {
        // given
        final var configProvider = new ConfigProviderImpl(true);
        final ServicesConfigurationList servicesConfigurationList = ServicesConfigurationList.newBuilder()
                .nameValue(
                        Setting.newBuilder().name("update.test1").value("789").build(),
                        Setting.newBuilder().name("update.test2").value("abc").build())
                .build();
        final Bytes bytes = ServicesConfigurationList.PROTOBUF.toBytes(servicesConfigurationList);

        // when
        configProvider.update(bytes, Bytes.EMPTY);
        VersionedConfiguration configuration = configProvider.getConfiguration();
        final String value1 = configuration.getValue("update.test1");
        final String value2 = configuration.getValue("update.test2");

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(value1).isEqualTo("789");
        assertThat(value2).isEqualTo("abc");
    }

    @Test
    void testUpdateProvidesConfigProperties2() {
        // given
        final var configProvider = new ConfigProviderImpl(true);
        final ServicesConfigurationList servicesConfigurationList = ServicesConfigurationList.newBuilder()
                .nameValue(
                        Setting.newBuilder().name("update.test1").value("789").build(),
                        Setting.newBuilder().name("update.test2").value("abc").build())
                .build();
        final Bytes bytes = ServicesConfigurationList.PROTOBUF.toBytes(servicesConfigurationList);

        // when
        configProvider.update(Bytes.EMPTY, bytes);
        VersionedConfiguration configuration = configProvider.getConfiguration();
        final String value1 = configuration.getValue("update.test1");
        final String value2 = configuration.getValue("update.test2");

        // then
        assertThat(configuration.getVersion()).isEqualTo(1);
        assertThat(value1).isEqualTo("789");
        assertThat(value2).isEqualTo("abc");
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testUpdateWithNullBytes() {
        // given
        final var configProvider = new ConfigProviderImpl(true);

        // then
        assertThatThrownBy(() -> configProvider.update(null, Bytes.EMPTY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> configProvider.update(Bytes.EMPTY, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUpdateWithInvalidBytes() {
        // given
        final var configProvider = new ConfigProviderImpl(true);
        final Bytes bytes = Bytes.wrap("\\uxxxx".getBytes(StandardCharsets.UTF_8));

        // then
        assertThatCode(() -> configProvider.update(bytes, Bytes.EMPTY)).doesNotThrowAnyException();
        assertThatCode(() -> configProvider.update(Bytes.EMPTY, bytes)).doesNotThrowAnyException();
    }
}
