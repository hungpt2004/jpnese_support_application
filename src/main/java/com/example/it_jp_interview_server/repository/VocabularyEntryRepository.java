package com.example.it_jp_interview_server.repository;

import com.example.it_jp_interview_server.model.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<VocabularyEntry> findAllByUserIdAndSetNameOrderByCreatedAtDesc(Long userId, String setName);

    Optional<VocabularyEntry> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndJapaneseIgnoreCase(Long userId, String japanese);

    long countByUserId(Long userId);

    long countByUserIdAndSetName(Long userId, String setName);

    @Query("select distinct entry.setName from VocabularyEntry entry where entry.userId = :userId")
    Set<String> findDistinctSetNamesByUserId(@Param("userId") Long userId);
}
