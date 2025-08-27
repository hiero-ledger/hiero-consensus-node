// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.config.legacy.JarAppConfig;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * This class only contains one method that was extracted from the {@link Browser} class.
 *
 * @deprecated will be replaced by the {@link com.swirlds.config.api.Configuration} API in near future once the
 * config.txt has been migrated to the regular config API. If you need to use this class please try to do as less static
 * access as possible.
 */
@Deprecated(forRemoval = true)
public final class ApplicationDefinitionLoader {

    private static final Logger logger = LogManager.getLogger(ApplicationDefinitionLoader.class);

    private ApplicationDefinitionLoader() {}

    /**
     * Creates an {@link ApplicationDefinition}, using the config file at the given path.
     *
     * @param pathsConfig the {@link PathsConfig} to use for bootstrapping
     * @param configPath  the path to the config file
     * @return an {@link ApplicationDefinition} specifying the application to be loaded and all related configuration
     * @throws ConfigurationException if the configuration cannot be loaded
     */
    public static @NonNull ApplicationDefinition loadDefault(
            @NonNull final PathsConfig pathsConfig, @NonNull final Path configPath) throws ConfigurationException {
        return load(pathsConfig, LegacyConfigPropertiesLoader.loadConfigFile(configPath));
    }

    /**
     * Creates a {@link ApplicationDefinition} from a given {@link LegacyConfigProperties}
     *
     * @param pathsConfig             the {@link PathsConfig} to use for bootstrapping
     * @param configurationProperties the {@link LegacyConfigProperties} to use for bootstrapping
     * @return an {@link ApplicationDefinition} specifying the application to be loaded and all related configuration
     * @throws ConfigurationException if the configuration cannot be loaded
     */
    public static @NonNull ApplicationDefinition load(
            @NonNull final PathsConfig pathsConfig, @NonNull final LegacyConfigProperties configurationProperties)
            throws ConfigurationException {
        Objects.requireNonNull(configurationProperties, "configurationProperties must not be null");

        final String swirldName = configurationProperties.swirldName().orElse("");

        final AddressBook addressBook = configurationProperties.getAddressBook();

        final AppStartParams appStartParams = configurationProperties
                .appConfig()
                .map(p -> ApplicationDefinitionLoader.convertToStartParams(pathsConfig, p))
                .orElseThrow(() -> new ConfigurationException("application config is missing"));

        return new ApplicationDefinition(
                swirldName,
                appStartParams.appParameters(),
                appStartParams.mainClassname(),
                appStartParams.appJarPath(),
                addressBook);
    }

    private static @NonNull AppStartParams convertToStartParams(
            @NonNull final PathsConfig pathsConfig, @NonNull final JarAppConfig appConfig) {
        final String[] appParameters = appConfig.params();
        // the line is: app, jarFilename, optionalParameters
        final String appJarFilename = appConfig.jarName();
        // this is a real .jar file, so load from data/apps/
        final Path appJarPath = pathsConfig.getAppsDirPath().resolve(appJarFilename);
        String mainClassname = "";
        try (final JarFile jarFile = new JarFile(appJarPath.toFile())) {
            final Manifest manifest = jarFile.getManifest();
            final Attributes attributes = manifest.getMainAttributes();
            mainClassname = attributes.getValue("Main-Class");
        } catch (final Exception e) {
            CommonUtils.tellUserConsolePopup("ERROR", "ERROR: Couldn't load app " + appJarPath);
            logger.error(EXCEPTION.getMarker(), "Couldn't find Main-Class name in jar file {}", appJarPath, e);
        }
        return new AppStartParams(appParameters, appJarFilename, mainClassname, appJarPath);
    }
}
