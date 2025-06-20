// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store;

import com.swirlds.platform.base.example.store.domain.Item;
import com.swirlds.platform.base.example.store.service.ItemService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class InitialData {

    private static final Logger logger = LogManager.getLogger(InitialData.class);

    private InitialData() {}

    /**
     * Populates seed data
     */
    public static void populate() {
        final ItemService service = new ItemService();
        service.create(new Item("Smartphone", "SKU-E-1", 20, "Electronics", null));
        service.create(new Item("Laptop", "SKU-C-1", 15, "Computers", null));
        service.create(new Item("Printer", "SKU-O-1", 25, "Office Equipment", null));
        service.create(new Item("Tablet", "SKU-E-2", 20, "Electronics", null));
        service.create(new Item("Desktop Computer", "SKU-C-2", 15, "Computers", null));
        service.create(new Item("Scanner", "SKU-O-2", 25, "Office Equipment", null));
        service.create(new Item("Smartwatch", "SKU-E-3", 20, "Electronics", null));
        service.create(new Item("Monitor", "SKU-C-3", 15, "Computers", null));
        service.create(new Item("Fax Machine", "SKU-O-3", 25, "Office Equipment", null));
        service.create(new Item("Digital Camera", "SKU-E-4", 20, "Electronics", null));
        service.create(new Item("Keyboard", "SKU-C-4", 15, "Computers", null));
        service.create(new Item("Projector", "SKU-O-4", 25, "Office Equipment", null));
        service.create(new Item("Headphones", "SKU-E-5", 20, "Electronics", null));
        service.create(new Item("Mouse", "SKU-C-5", 15, "Computers", null));
        service.create(new Item("Desk Lamp", "SKU-O-5", 25, "Office Equipment", null));
        service.create(new Item("Digital Thermometer", "SKU-E-6", 20, "Electronics", null));
        service.create(new Item("External Hard Drive", "SKU-C-6", 15, "Computers", null));
        service.create(new Item("Shredder", "SKU-O-6", 25, "Office Equipment", null));
        service.create(new Item("Wireless Speaker", "SKU-E-7", 20, "Electronics", null));
        service.create(new Item("Printer Paper", "SKU-C-7", 15, "Computers", null));
        service.create(new Item("Desk Organizer", "SKU-O-7", 25, "Office Equipment", null));
        service.create(new Item("USB Flash Drive", "SKU-E-8", 20, "Electronics", null));
        service.create(new Item("Wireless Router", "SKU-C-8", 15, "Computers", null));
        service.create(new Item("Paper Shredder", "SKU-O-8", 25, "Office Equipment", null));
        service.create(new Item("GPS Navigator", "SKU-E-9", 20, "Electronics", null));
        service.create(new Item("Ink Cartridge", "SKU-C-9", 15, "Computers", null));
        service.create(new Item("Desk Chair", "SKU-O-9", 25, "Office Equipment", null));
        service.create(new Item("Bluetooth Earphones", "SKU-E-10", 20, "Electronics", null));
        service.create(new Item("External Monitor", "SKU-C-10", 15, "Computers", null));
        service.create(new Item("Stapler", "SKU-O-10", 25, "Office Equipment", null));
        logger.debug("Seed data added");
    }
}
