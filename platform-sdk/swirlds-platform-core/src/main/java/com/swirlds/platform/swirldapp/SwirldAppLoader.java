// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.swirldapp;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.SwirldMain;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import org.hiero.base.constructable.URLClassLoaderWithLookup;

public class SwirldAppLoader {
    /** the name of the app class inheriting from {@link SwirldMain} */
    private String mainClassName;
    /** the location of the JAR where the apps classes are located */
    private Path appJarPath;
    /** the constructor for the main program of the app */
    private Constructor<?> appMainConstructor;
    /** The classloader used to load the app */
    private URLClassLoaderWithLookup classLoader;

    private SwirldAppLoader(
            final String mainClassName,
            final Path appJarPath,
            final Constructor<?> appMainConstructor,
            final URLClassLoaderWithLookup classLoader) {
        this.mainClassName = mainClassName;
        this.appJarPath = appJarPath;
        this.appMainConstructor = appMainConstructor;
        this.classLoader = classLoader;
    }

    /**
     * Creates a new instance of {@link SwirldMain} and returns it
     *
     * @return a new instance of {@link SwirldMain}
     * @throws AppLoaderException
     * 		if any issue occurs while instantiating the object
     */
    public SwirldMain<? extends MerkleNodeState> instantiateSwirldMain() throws AppLoaderException {
        try {
            return (SwirldMain<? extends MerkleNodeState>) appMainConstructor.newInstance();
        } catch (InstantiationException | InvocationTargetException e) {
            throw new AppLoaderException("ERROR: Couldn't instantiate the class " + mainClassName, e);
        } catch (IllegalAccessException e) {
            throw new AppLoaderException("ERROR: Couldn't access the class " + mainClassName, e);
        }
    }

    /**
     * @param mainClassName
     * 		the name of the app class inheriting from {@link SwirldMain}
     * @param appJarPath
     * 		the location of the JAR where the apps classes are located
     * @return the SwirldAppLoader object
     * @throws AppLoaderException
     * 		if any problems occur while loading the app
     */
    public static SwirldAppLoader loadSwirldApp(final String mainClassName, final Path appJarPath)
            throws AppLoaderException {

        URLClassLoaderWithLookup classLoader;
        try {
            classLoader = new URLClassLoaderWithLookup(
                    new URL[] {appJarPath.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader());
            Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
            Constructor<?>[] constructors = mainClass.getDeclaredConstructors();
            Constructor<?> constructor = null;
            for (Constructor<?> constructor2 : constructors) {
                if (constructor2.getGenericParameterTypes().length == 0) {
                    constructor = constructor2;
                    break;
                }
            }
            if (constructor == null) {
                throw new AppLoaderException("The class '" + mainClassName + "' must have a default constructor");
            }

            return new SwirldAppLoader(mainClassName, appJarPath, constructor, classLoader);
        } catch (ClassNotFoundException e) {
            throw new AppLoaderException("ERROR: Couldn't find the class \"" + mainClassName + "\"", e);
        } catch (Exception e) {
            throw new AppLoaderException("ERROR: There are problems loading the class " + mainClassName, e);
        }
    }

    /**
     * @return the classloader used to load the app
     */
    public URLClassLoaderWithLookup getClassLoader() {
        return classLoader;
    }
}
