package com.example.it_jp_interview_server.repository;

import com.example.it_jp_interview_server.model.VocabularyBook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VocabularyBookRepository extends JpaRepository<VocabularyBook, Long> {
    List<VocabularyBook> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<VocabularyBook> findTop50ByIsPublicTrueOrderByCreatedAtDesc();

    Optional<VocabularyBook> findByIdAndUserId(Long id, Long userId);

    Optional<VocabularyBook> findByUserIdAndNameIgnoreCase(Long userId, String name);
}
