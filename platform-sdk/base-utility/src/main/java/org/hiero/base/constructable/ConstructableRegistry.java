// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

/**
 * A central registry of constructors for {@link RuntimeConstructable} classes. This central registry has a set of
 * sub-registries based on the constructor type of the class.
 * @deprecated should be removed once all serialization moves to PBJ
 */
@Deprecated(forRemoval = true)
public interface ConstructableRegistry {

    ConstructableRegistry INSTANCE = ConstructableRegistryFactory.createConstructableRegistry();

    static ConstructableRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Returns a {@link ConstructorRegistry} for the constructor type requested, or null if no such registry exists
     *
     * @param constructorType
     * 		a class that represents the type of constructor used to instantiate classes
     * @param <T>
     * 		the type of constructor used to instantiate classes
     * @return a constructor registry or null
     */
    <T> ConstructorRegistry<T> getRegistry(Class<T> constructorType);

    /**
     * Returns the no-arg constructor for the {@link RuntimeConstructable} if previously registered. If no
     * constructor is found, it returns null. This method will always return null unless
     * {@link #registerConstructable(ClassConstructorPair)} is called beforehand.
     *
     * @param classId
     * 		the unique class ID to get the constructor for
     * @return the constructor of the class, or null if no constructor is registered
     * @deprecated should be replaced with {@link #getRegistry(Class)} with the parameter {@link NoArgsConstructor}
     * 		and then {@link ConstructorRegistry#getConstructor(long)}
     */
    @Deprecated(forRemoval = true)
    NoArgsConstructor getConstructor(final long classId);

    /**
     * Instantiates an object of a class defined by the supplied {@code classId}. If no object is registered with this
     * ID, it will return null. This method will always return null unless {@link #registerConstructable(ClassConstructorPair)} is
     * called beforehand.
     *
     * @param classId
     * 		the unique class ID to create an object of
     * @param <T>
     * 		the type of the object
     * @return an instance of the class, or null if no such class is registered
     * @deprecated should be replaced with {@link #getRegistry(Class)} with the parameter {@link NoArgsConstructor}
     * 		and then {@link ConstructorRegistry#getConstructor(long)}, and then {@link NoArgsConstructor#get()}
     */
    @Deprecated(forRemoval = true)
    <T extends RuntimeConstructable> T createObject(final long classId);

    /**
     * Register the provided {@link ClassConstructorPair} so that it can be instantiated based on its class ID
     *
     * @param pair
     * 		the ClassConstructorPair to register
     * @throws ConstructableRegistryException
     * 		thrown if constructor cannot be registered for any reason
     */
    void registerConstructable(ClassConstructorPair pair) throws ConstructableRegistryException;

    /**
     * Reset the registry. For testing purposes only.
     */
    void reset();
}
