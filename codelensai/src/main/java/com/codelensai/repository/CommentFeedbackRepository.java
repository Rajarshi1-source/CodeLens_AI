package com.codelensai.repository;

import com.codelensai.model.entity.CommentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentFeedbackRepository extends JpaRepository<CommentFeedback, Long> {
}
