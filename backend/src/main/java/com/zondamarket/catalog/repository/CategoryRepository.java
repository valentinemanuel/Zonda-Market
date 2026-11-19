package com.zondamarket.catalog.repository;

import com.zondamarket.catalog.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CategoryRepository extends Repository<Category, UUID> {
    String LEAF = "c.deleted = false and not exists "
            + "(select 1 from Category child where child.parentId = c.id and child.deleted = false)";

    Optional<Category> findByIdAndDeletedFalse(UUID id);

    @Query("from Category c where " + LEAF + " order by c.name, c.id")
    List<Category> findLeaves();

    @Query("from Category c where c.id = :id and " + LEAF)
    Optional<Category> findLeaf(UUID id);
}
