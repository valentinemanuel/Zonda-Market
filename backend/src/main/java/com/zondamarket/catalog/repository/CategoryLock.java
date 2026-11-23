package com.zondamarket.catalog.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryLock {
    private final JdbcClient sql;

    CategoryLock(JdbcClient sql) {
        this.sql = sql;
    }

    // Acquire before listing/SKU locks. Sellers and checkouts can proceed concurrently.
    public void readForWrite() {
        sql.sql("LOCK TABLE zonda_market.category IN SHARE MODE").update();
    }

    public void changeTree() {
        sql.sql("LOCK TABLE zonda_market.category IN SHARE ROW EXCLUSIVE MODE").update();
    }
}
