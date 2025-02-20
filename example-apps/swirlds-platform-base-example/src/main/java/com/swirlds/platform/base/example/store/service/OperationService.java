// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.service;

import static java.util.Objects.isNull;

import com.google.common.base.Preconditions;
import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.server.CrudService;
import com.swirlds.platform.base.example.store.domain.Inventory;
import com.swirlds.platform.base.example.store.domain.Item;
import com.swirlds.platform.base.example.store.domain.Operation;
import com.swirlds.platform.base.example.store.domain.Operation.OperationDetail;
import com.swirlds.platform.base.example.store.domain.OperationType;
import com.swirlds.platform.base.example.store.domain.Stock;
import com.swirlds.platform.base.example.store.domain.StockHandlingMode;
import com.swirlds.platform.base.example.store.metrics.StoreExampleMetrics;
import com.swirlds.platform.base.example.store.persistence.InventoryDao;
import com.swirlds.platform.base.example.store.persistence.ItemDao;
import com.swirlds.platform.base.example.store.persistence.OperationDao;
import com.swirlds.platform.base.example.store.persistence.OperationDao.Criteria;
import com.swirlds.platform.base.example.store.persistence.StockDao;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Creates Operations
 */
public class OperationService extends CrudService<Operation> {

    private static final Logger logger = LogManager.getLogger(OperationService.class);
    private final OperationDao operationDao;
    private final InventoryDao inventoryDao;
    private final StockDao stockDao;
    private final BaseContext context;
    private final ItemDao itemDao;

    public OperationService(@NonNull final BaseContext context) {
        super(Operation.class);
        this.context = Objects.requireNonNull(context, "context cannot be null");
        this.operationDao = OperationDao.getInstance();
        this.stockDao = StockDao.getInstance();
        this.inventoryDao = InventoryDao.getInstance();
        this.itemDao = ItemDao.getInstance();
    }

    @NonNull
    @Override
    public Operation create(@NonNull final Operation operation) {
        final long startNano = System.nanoTime();
        Objects.requireNonNull(operation, "operation cannot be null");
        Preconditions.checkArgument(operation.type() != null, "Operation#type cannot be null");
        Preconditions.checkArgument(operation.details() != null, "Operation#details cannot be null");
        Preconditions.checkArgument(!operation.details().isEmpty(), "Operation#details cannot be empty");

        operation.details().forEach(d -> {
            Preconditions.checkArgument(d.itemId() != null, "OperationDetails#itemId cannot be null");
            Preconditions.checkArgument(d.amount() != null, "OperationDetails#amount cannot be null");
            Preconditions.checkArgument(d.amount() >= 0, "OperationDetails#amount cannot be less than or equals to 0");
        });

        final Map<String, Inventory> existences =
                new HashMap<>(operation.details().size());
        final Map<String, Integer> operationAmount = operation.details().stream()
                .collect(Collectors.toMap(OperationDetail::itemId, OperationDetail::amount));
        for (OperationDetail od : operation.details()) {
            existences.put(od.itemId(), inventoryDao.findWithBlockForUpdate(od.itemId())); // Locks the stock for update
        }

        final String notFoundItem = existences.entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);

        final Map<String, Integer> updatedAmounts = existences.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(
                        Entry::getKey,
                        e -> operation.type().apply(e.getValue().amount(), operationAmount.get(e.getKey()))));

        final String notEnoughStockItem = updatedAmounts.entrySet().stream()
                .filter(e -> e.getValue() < 0)
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);

        final String belowStockLevelItem = updatedAmounts.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .filter(e -> {
                    final Item byId = itemDao.findById(e.getKey());
                    return e.getValue() < existences.get(e.getKey()).amount()
                            && byId.minimumStockLevel() >= e.getValue();
                })
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);

        if (Objects.nonNull(notFoundItem)) {
            existences.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(Entry::getKey)
                    .forEach(inventoryDao::release);
            logger.error("item not found:{}", notFoundItem);
            throw new IllegalArgumentException("Item " + notFoundItem + " not found in the system");
        }
        if (Objects.nonNull(notEnoughStockItem)) {
            existences.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(Entry::getKey)
                    .forEach(inventoryDao::release);
            logger.error("item with not enough stock: {}", notEnoughStockItem);
            throw new IllegalArgumentException("Item " + notEnoughStockItem + " has not enough stock");
        }
        if (Objects.nonNull(belowStockLevelItem)) {
            existences.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(Entry::getKey)
                    .forEach(inventoryDao::release);
            logger.error("item with not enough stock: {}", belowStockLevelItem);
            throw new IllegalArgumentException(
                    "Item " + belowStockLevelItem + " would be below the stock minimum amount");
        }

        final Operation saved = operationDao.save(operation);
        updatedAmounts.forEach((k, v) -> inventoryDao.saveOrUpdate(new Inventory(k, v)));

        if (saved.type() == OperationType.ADDITION) {
            operation.details().forEach(d -> {
                stockDao.save(
                        new Stock(d.itemId(), d.unitaryPrice(), saved.timestamp(), new AtomicInteger(d.amount())));
            });
        } else if (saved.type() == OperationType.DEDUCTION) {
            operation.details().forEach(d -> {
                int amount = d.amount();
                for (Stock s : stockDao.getAllWithRemaining(d.itemId()).stream()
                        .sorted(getComparator(d))
                        .toList()) {

                    final int value = s.remaining().get() - d.amount();
                    if (value >= 0) {
                        s.remaining().getAndSet(value);
                        amount -= value;
                    } else {
                        amount -= s.remaining().getAndSet(0);
                    }

                    if (amount <= 0) {
                        break;
                    }
                }
            });
        }
        existences.keySet().forEach(inventoryDao::release);

        final Duration duration = Duration.of(System.nanoTime() - startNano, ChronoUnit.NANOS);
        context.metrics().getOrCreate(StoreExampleMetrics.OPERATION_TIME).set(duration);
        context.metrics().getOrCreate(StoreExampleMetrics.OPERATION_TOTAL).increment();
        logger.debug("Executed operation {} in {}", saved, duration);
        return saved;
    }

    private @NonNull Comparator<? super Stock> getComparator(final @Nullable OperationDetail detail) {
        if (isNull(detail) || StockHandlingMode.FIFO == detail.mode()) {
            return Comparator.comparing(Stock::timestamp);
        } else {
            return Comparator.comparing(Stock::timestamp).reversed();
        }
    }

    @NonNull
    @Override
    public List<Operation> retrieveAll(@NonNull final Map<String, String> params) {
        Objects.requireNonNull(params, "params must not be null");
        final Criteria criteria;
        try {
            criteria = Criteria.fromMap(params);
        } catch (Exception e) {
            throw new IllegalArgumentException("Error parsing parameters for search" + params);
        }
        return operationDao.findByCriteria(criteria);
    }
}
