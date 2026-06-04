package com.codelensai.repository;

import com.codelensai.model.entity.ReviewCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewCommentRepository extends JpaRepository<ReviewCommentEntity, Long> {

    List<ReviewCommentEntity> findByPrIdOrderByFilePathAscLineNumberAsc(Long prId);

    List<ReviewCommentEntity> findBySessionId(Long sessionId);

    @Modifying
    @Query("delete from ReviewCommentEntity c where c.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);

    @Query("select c.severity, count(c) from ReviewCommentEntity c group by c.severity")
    List<Object[]> countBySeverity();
}
