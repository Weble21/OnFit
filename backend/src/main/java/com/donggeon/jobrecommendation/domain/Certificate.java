package com.donggeon.jobrecommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "certificates")
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 200)
    private String issuer;

    @Column(name = "acquired_on")
    private LocalDate acquiredOn;

    // Score or grade as written on the certificate ("900", "IH", "최종합격").
    @Column(length = 50)
    private String score;

    protected Certificate() {
    }

    public Certificate(String name, String issuer, LocalDate acquiredOn, String score) {
        this.name = name;
        this.issuer = issuer;
        this.acquiredOn = acquiredOn;
        this.score = score;
    }

    void setProfile(UserProfile profile) { this.profile = profile; }

    public Long getId() { return id; }
    public UserProfile getProfile() { return profile; }
    public String getName() { return name; }
    public String getIssuer() { return issuer; }
    public LocalDate getAcquiredOn() { return acquiredOn; }
    public String getScore() { return score; }
}
