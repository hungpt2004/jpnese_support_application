package com.example.it_jp_interview_server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "vocabulary_entries")
public class VocabularyEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String japanese;

    @Column(nullable = false, length = 100)
    private String setName;

    @Column(length = 200)
    private String reading;

    @Column(length = 500)
    private String vietnamese;

    @Column(length = 600)
    private String example;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected VocabularyEntry() {
    }

    public VocabularyEntry(Long userId, String japanese, String reading, String vietnamese, String example) {
        this(userId, japanese, "Từ vựng của tôi", reading, vietnamese, example);
    }

    public VocabularyEntry(Long userId, String japanese, String setName, String reading, String vietnamese, String example) {
        this.userId = userId;
        this.japanese = japanese;
        this.setName = setName;
        this.reading = reading;
        this.vietnamese = vietnamese;
        this.example = example;
    }

    public Long getId() {
        return id;
    }

    public String getJapanese() {
        return japanese;
    }

    public String getSetName() {
        return setName;
    }

    public String getReading() {
        return reading;
    }

    public String getVietnamese() {
        return vietnamese;
    }

    public String getExample() {
        return example;
    }
}
