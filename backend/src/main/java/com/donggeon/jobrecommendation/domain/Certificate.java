package com.doggeon.jobrecommendation.domain;

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

    protected Certificate() {
    }

    public Certificate(String name, String issuer, LocalDate acquiredOn) {
        this.name = name;
        this.issuer = issuer;
        this.acquiredOn = acquiredOn;
    }

    void setProfile(UserProfile profile) { this.profile = profile; }

    public Long getId() { return id; }
    public UserProfile getProfile() { return profile; }
    public String getName() { return name; }
    public String getIssuer() { return issuer; }
    public LocalDate getAcquiredOn() { return acquiredOn; }
}
