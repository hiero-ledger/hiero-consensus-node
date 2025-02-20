// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.persistence;

import com.swirlds.platform.base.example.store.domain.Item;
import com.swirlds.platform.base.example.store.persistence.exception.EntityNotFoundException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-memory simple data layer for Items
 */
public class ItemDao {

    private static class InstanceHolder {
        private static final ItemDao INSTANCE = new ItemDao();
    }

    public static @NonNull ItemDao getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static final Map<UUID, Item> ITEMS = new ConcurrentHashMap<>();

    public @NonNull Item save(@NonNull final Item item) {
        Objects.requireNonNull(item, "item must not be null");
        UUID uuid;
        if (Objects.isNull(item.id())) {
            uuid = UUID.randomUUID();
            while (ITEMS.containsKey(uuid)) {
                uuid = UUID.randomUUID();
            }
        } else {
            uuid = UUID.fromString(item.id());
            if (!ITEMS.containsKey(uuid)) {
                throw new EntityNotFoundException(Item.class, item.id());
            }
        }
        final Item value =
                new Item(item.description(), item.sku(), item.minimumStockLevel(), item.category(), uuid.toString());
        ITEMS.put(uuid, value);
        return value;
    }

    public @NonNull Item findById(@NonNull final String id) {
        if (!ITEMS.containsKey(UUID.fromString(id))) {
            throw new EntityNotFoundException(Item.class, id);
        }
        return ITEMS.get(UUID.fromString(id));
    }

    public void deleteById(@NonNull final String id) {
        if (!ITEMS.containsKey(UUID.fromString(id))) {
            throw new EntityNotFoundException(Item.class, id);
        }
        ITEMS.remove(UUID.fromString(id));
    }

    public @NonNull List<Item> findAll() {
        return ITEMS.values().stream().toList();
    }

    public @NonNull Integer countAll() {
        return ITEMS.size();
    }
}
