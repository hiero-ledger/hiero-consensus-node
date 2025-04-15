// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

/**
 * An algorithm instance caching {@link OperationProvider} implementation.  Provides an abstraction that
 * allows a single implementation to support multiple algorithms of the same type and a plugin model for switching
 * provider implementations with ease. Generic enough to support multiple types of cryptographic transformations.
 *
 * This class guarantees that {@link ThreadLocal} caching will be used to cache all algorithm implementation instances
 * returned from the {@link #handleAlgorithmRequired(Enum)} method. Subclass implementors should not override the {@link
 * #loadAlgorithm(Enum)} method. Instead subclasses should implement the {@link #handleAlgorithmRequired(Enum)} in order
 * to load an algorithm implementation.
 *
 * Not all implementations need to use the OptionalData field. For these implementations, the object of type
 * Element contains all data required to perform the operation. For classes that do not utilize OptionalData,
 * it is recommended to set it to type Void.
 *
 * @param <Element>
 * 		the type of the input to be transformed
 * @param <OptionalData>
 * 		the type of optional data to be used when performing the operation, may be Void type
 * @param <Result>
 * 		the type of the output resulting from the transformation
 * @param <Alg>
 * 		the type of the algorithm implementation
 * @param <AlgType>
 * 		the type of the enumeration providing the list of available algorithms
 */
public abstract class CachingOperationProvider<Element, OptionalData, Result, Alg, AlgType extends Enum<AlgType>>
        extends OperationProvider<Element, OptionalData, Result, Alg, AlgType> {

    private final ThreadLocal<HashMap<AlgType, Alg>> context = ThreadLocal.withInitial(HashMap::new);

    /**
     * Default Constructor. Initializes the thread local cache.
     */
    public CachingOperationProvider() {
        super();
    }

    /**
     * Loads a concrete implementation of the required algorithm. This method may choose to return the same instance
     * or a new instance of the algorithm on each invocation.
     *
     * @param algorithmType
     * 		the type of algorithm to be loaded
     * @return a concrete implementation of the required algorithm
     * @throws NoSuchAlgorithmException
     * 		if an implementation of the required algorithm cannot be located or loaded
     * @see CachingOperationProvider#loadAlgorithm(Enum)
     * @see CachingOperationProvider#handleAlgorithmRequired(Enum)
     */
    @Override
    protected Alg loadAlgorithm(final AlgType algorithmType) throws NoSuchAlgorithmException {
        final HashMap<AlgType, Alg> algorithmCache = context.get();

        if (algorithmCache.containsKey(algorithmType)) {
            return algorithmCache.get(algorithmType);
        }

        final Alg algorithm = handleAlgorithmRequired(algorithmType);
        algorithmCache.putIfAbsent(algorithmType, algorithm);

        return algorithm;
    }

    /**
     * Called by the {@link #loadAlgorithm(Enum)} method to load an algorithm implementation. Only called if there
     * was no instance already cached. Will only be called once per algorithm type on each thread.
     *
     * @param algorithmType
     * 		the type of algorithm to be loaded
     * @return a concrete implementation of the required algorithm
     * @throws NoSuchAlgorithmException
     * 		if an implementation of the required algorithm cannot be located or loaded
     * @see OperationProvider#loadAlgorithm(Enum)
     */
    protected abstract Alg handleAlgorithmRequired(final AlgType algorithmType) throws NoSuchAlgorithmException;
}
