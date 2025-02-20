// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;

import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.gui.model.InfoApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Maintain all the metadata about the state hierarchy, which has 4 levels: (app, swirld, member, state).
 * This includes the list of every app installed locally. For each app, it includes the list of all known
 * swirlds running on it. For each swirld, it maintains the list of all known members stored locally. For
 * each member it maintains the list of all states stored locally. Each app, swirld, member, and state also
 * stores its name, and its parent in the hierarchy.
 */
public class StateHierarchy {
    /** list of the known apps, each of which may have some members, swirlds, and states */
    List<InfoApp> apps = new ArrayList<>();

    /**
     * Create the state hierarchy, by finding all .jar files in data/apps/ and also adding in a virtual one
     * named fromAppName if that parameter is non-null.
     *
     * @param fromAppName
     * 		the name of the virtual data/apps/*.jar file, or null if none.
     */
    public StateHierarchy(final String fromAppName) {
        final Path appsDirPath = getAbsolutePath().resolve("data").resolve("apps");
        final List<Path> appFiles = rethrowIO(() -> Files.list(appsDirPath)
                .filter(path -> path.toString().endsWith(".jar"))
                .toList());
        final List<String> names = new ArrayList<>();

        if (fromAppName != null) { // if there's a virtual jar, list it first
            names.add(fromAppName);
            final List<Path> toDelete = rethrowIO(() -> Files.list(appsDirPath)
                    .filter(path -> path.getFileName().toString().equals(fromAppName))
                    .toList());
            if (toDelete != null) {
                for (final Path file : toDelete) {
                    rethrowIO(() -> Files.deleteIfExists(file));
                }
            }
        }

        if (appFiles != null) {
            for (final Path app : appFiles) {
                names.add(getMainClass(app.toAbsolutePath().toString()));
            }
        }

        names.sort(null);
        for (String name : names) {
            name = name.substring(0, name.length() - 4);
            apps.add(new InfoApp(name));
        }
    }

    private static String getMainClass(final String appJarPath) {
        String mainClassname;
        try (final JarFile jarFile = new JarFile(appJarPath)) {
            final Manifest manifest = jarFile.getManifest();
            final Attributes attributes = manifest.getMainAttributes();
            mainClassname = attributes.getValue("Main-Class");
            return mainClassname;
        } catch (Exception ex) {
            CommonUtils.tellUserConsolePopup("ERROR", "ERROR: Couldn't load app " + appJarPath);
            return null;
        }
    }

    /**
     * Get the InfoApp for an app stored locally, given the name of the jar file (without the ".jar").
     *
     * @param name
     * 		name the jar file, without the ".jar" at the end
     * @return the InfoApp for that app
     */
    public InfoApp getInfoApp(String name) {
        for (InfoApp app : apps) {
            if (name.equals(app.getName())) {
                return app;
            }
        }
        return null;
    }
}
