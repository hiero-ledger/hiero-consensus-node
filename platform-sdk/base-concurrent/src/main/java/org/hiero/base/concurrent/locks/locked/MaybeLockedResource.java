// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.locked;

/**
 * A resource that may or may not have been locked
 *
 * @param <T>
 * 		the type of resource
 */
public interface MaybeLockedResource<T> extends LockedResource<T>, MaybeLocked {}
