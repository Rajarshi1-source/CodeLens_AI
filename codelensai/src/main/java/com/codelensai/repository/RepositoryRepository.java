package com.codelensai.repository;

import com.codelensai.model.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data access for the {@link Repository} entity (the {@code repositories} table). */
public interface RepositoryRepository extends JpaRepository<Repository, Long> {
    Optional<Repository> findByFullName(String fullName);

    List<Repository> findByUserId(Long userId);
}
