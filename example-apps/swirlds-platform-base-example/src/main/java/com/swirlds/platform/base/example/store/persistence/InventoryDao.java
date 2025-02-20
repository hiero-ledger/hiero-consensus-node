// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.persistence;

import com.swirlds.platform.base.example.store.domain.Inventory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * in-memory simple data layer for Inventory
 */
public class InventoryDao {

    private static class InstanceHolder {
        private static final InventoryDao INSTANCE = new InventoryDao();
    }

    public static @NonNull InventoryDao getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static final Map<String, InnerItemExistence> INVENTORY = new ConcurrentHashMap<>();

    /**
     * Saves or updates the object
     */
    public @NonNull Inventory saveOrUpdate(final @NonNull Inventory existence) {
        Objects.requireNonNull(existence.itemId(), "itemId must not be null");
        final InnerItemExistence ie = this.internalFindWithBlockForUpdate(existence.itemId());
        if (ie == null) {
            INVENTORY.put(existence.itemId(), new InnerItemExistence(existence.amount()));
        } else {
            INVENTORY.put(existence.itemId(), new InnerItemExistence(existence.amount(), ie.lock));
            ie.lock.unlock();
        }
        return existence;
    }

    public @Nullable Inventory findByItemId(final @NonNull String itemId) {
        final InnerItemExistence internalBalance = INVENTORY.get(itemId);
        if (internalBalance == null) {
            return null;
        }
        return new Inventory(itemId, internalBalance.amount);
    }

    /**
     * Retrieves the object with a lock for update. No other write operations can be performed against the object until
     * released.
     */
    public @Nullable Inventory findWithBlockForUpdate(final @NonNull String itemId) {
        final InnerItemExistence itemExistence = internalFindWithBlockForUpdate(itemId);
        if (itemExistence == null) {
            return null;
        }
        return new Inventory(itemId, itemExistence.amount);
    }

    /**
     * Release the write lock. Must be hold by the thread.
     */
    public void release(final @NonNull String itemId) {
        final InnerItemExistence itemExistence = INVENTORY.get(itemId);
        if (itemExistence != null) {
            itemExistence.lock.unlock();
        }
    }

    public void deleteByItemId(final String itemId) {
        if (INVENTORY.get(itemId).lock.isHeldByCurrentThread()) {
            INVENTORY.get(itemId).lock.unlock();
        }
        INVENTORY.remove(itemId);
    }

    private @Nullable InnerItemExistence internalFindWithBlockForUpdate(final @NonNull String itemId) {
        final InnerItemExistence itemExistence = INVENTORY.get(itemId);
        if (itemExistence == null) {
            return null;
        }
        itemExistence.lock.lock();
        return itemExistence;
    }

    private record InnerItemExistence(Integer amount, ReentrantLock lock) {
        public InnerItemExistence(final Integer amount) {
            this(amount, new ReentrantLock());
        }
    }
}
