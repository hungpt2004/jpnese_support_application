package com.example.it_jp_interview_server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "vocabulary_books")
public class VocabularyBook {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description = "";

    @Column(nullable = false)
    private boolean isPublic;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected VocabularyBook() {
    }

    public VocabularyBook(Long userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
