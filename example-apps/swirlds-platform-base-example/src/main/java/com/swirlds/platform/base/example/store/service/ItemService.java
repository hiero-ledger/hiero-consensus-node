// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.service;

import com.swirlds.platform.base.example.server.CrudService;
import com.swirlds.platform.base.example.store.domain.Inventory;
import com.swirlds.platform.base.example.store.domain.Item;
import com.swirlds.platform.base.example.store.persistence.InventoryDao;
import com.swirlds.platform.base.example.store.persistence.ItemDao;
import com.swirlds.platform.base.example.store.persistence.StockDao;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controls items crud
 */
public class ItemService extends CrudService<Item> {

    private final ItemDao dao;
    private final InventoryDao inventoryDao;

    public ItemService() {
        super(Item.class);
        this.dao = ItemDao.getInstance();
        this.inventoryDao = InventoryDao.getInstance();
    }

    @Override
    public void delete(@NonNull final String itemId) {
        inventoryDao.findWithBlockForUpdate(itemId);
        StockDao.getInstance().deleteByItemId(itemId);
        dao.deleteById(itemId);
        inventoryDao.release(itemId);
        inventoryDao.deleteByItemId(itemId);
    }

    @NonNull
    @Override
    public Item create(@NonNull final Item body) {
        final Item save = dao.save(body);
        Objects.requireNonNull(save.id(), "id must not be null");
        if (!Objects.equals(body.id(), save.id())) { // Is creation
            inventoryDao.saveOrUpdate(new Inventory(save.id(), 0));
        }
        return save;
    }

    @NonNull
    @Override
    public Item retrieve(@NonNull final String key) {
        return dao.findById(key);
    }

    @NonNull
    @Override
    public List<Item> retrieveAll(@NonNull final Map<String, String> params) {
        return dao.findAll();
    }
}
