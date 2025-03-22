// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common;

import com.swirlds.base.state.Mutable;
import org.hiero.consensus.model.crypto.Hashable;

/**
 * An interface for classes that can be copied and serialized in a way specific to the Swirlds platform. If a class
 * implements the FastCopyable interface, then it should use a copy-on-write strategy so that calls to {@link #copy}
 * make virtual copies almost instantaneously. See the documentation for these methods for the details on what they
 * should do, and how they differ from the usual Java <code>copy</code> and
 * <code>serialize</code> methods.
 */
public interface FastCopyable extends Copyable, Mutable, Releasable {

    /**
     * If a class ExampleClass implements the FastCopyable interface, and an object x is of class
     * ExampleClass, then x.copy() instantiates and returns a new ExampleClass object containing the same
     * data as x. This should be a deep clone, not shallow. So if x contains references to other objects, it
     * should do something like copy() on them, too, rather than just copying the reference.
     * <p>
     * Furthermore, the copy operation should be fast, at the possible cost of making reads and writes
     * slower (such as by using some kind of copy-on-write mechanism).
     * <strong>This method causes the object to become immutable and returns a mutable copy.</strong>
     * If the object is already immutable:
     * <ol>
     *     <li>it can throw an MutabilityException</li>
     *     <li>or it can implement a slow, deep copy that returns a mutable object </li>
     * </ol>
     *
     * Either behavior is fine, but each implementation should document which behavior it has chosen.
     * By the default, the first implementation is assumed.
     *
     * If a FastCopyable object extends {@link Hashable} then under no circumstances should
     * the hash be copied by this method.
     *
     * It is strongly suggested that each implementing class override the return type of this method to its self
     * type. So if class Foo extends FastCopyable then Foo's copy signature should look like "public Foo copy()".
     *
     * @return the new copy that was made
     */
    @Override
    <T extends Copyable> T copy();

    /**
     * Determines if an object/copy is immutable or not.
     * Only the most recent copy must be mutable.
     *
     * @return Whether the object is immutable or not
     */
    @Override
    default boolean isImmutable() {
        return true;
    }
}
