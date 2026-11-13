package com.donggeon.jobrecommendation.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "profile_activities")
public class ProfileActivity {
    public enum Kind { TRAINING, ACTIVITY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 200)
    private String organization;
    @Column(columnDefinition = "text")
    private String description;
    @Column(name = "started_on")
    private LocalDate startedOn;
    @Column(name = "ended_on")
    private LocalDate endedOn;

    protected ProfileActivity() {}
    public ProfileActivity(Kind kind, String title, String organization, String description,
                           LocalDate startedOn, LocalDate endedOn) {
        this.kind = kind; this.title = title; this.organization = organization;
        this.description = description; this.startedOn = startedOn; this.endedOn = endedOn;
    }
    void setProfile(UserProfile profile) { this.profile = profile; }
    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getOrganization() { return organization; }
    public String getDescription() { return description; }
    public LocalDate getStartedOn() { return startedOn; }
    public LocalDate getEndedOn() { return endedOn; }
}
