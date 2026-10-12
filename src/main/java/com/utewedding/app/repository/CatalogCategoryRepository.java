package com.utewedding.app.repository;

import com.utewedding.app.domain.entity.CategoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Category queries are kept out of MVC controllers. */
public interface CatalogCategoryRepository extends JpaRepository<CategoryEntity, Long> {
    List<CategoryEntity> findByActiveTrueOrderByDisplayOrderAscNameAsc();
    Optional<CategoryEntity> findBySlugIgnoreCase(String slug);
    Optional<CategoryEntity> findByNameIgnoreCase(String name);
}
