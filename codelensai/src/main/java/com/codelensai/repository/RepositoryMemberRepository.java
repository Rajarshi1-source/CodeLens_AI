package com.codelensai.repository;

import com.codelensai.model.entity.RepositoryMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepositoryMemberRepository extends JpaRepository<RepositoryMember, RepositoryMember.Key> {

    boolean existsByRepoIdAndUserId(Long repoId, Long userId);

    List<RepositoryMember> findByUserId(Long userId);
}
