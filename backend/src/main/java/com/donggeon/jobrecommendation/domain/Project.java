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
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "tech_stack", columnDefinition = "text")
    private String techStack;

    @Column(name = "started_on")
    private LocalDate startedOn;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    @Column(name = "project_url", length = 1000)
    private String projectUrl;

    protected Project() {
    }

    public Project(String name, String description, String techStack, LocalDate startedOn,
                   LocalDate endedOn, String projectUrl) {
        this.name = name;
        this.description = description;
        this.techStack = techStack;
        this.startedOn = startedOn;
        this.endedOn = endedOn;
        this.projectUrl = projectUrl;
    }

    void setProfile(UserProfile profile) { this.profile = profile; }

    public Long getId() { return id; }
    public UserProfile getProfile() { return profile; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getTechStack() { return techStack; }
    public LocalDate getStartedOn() { return startedOn; }
    public LocalDate getEndedOn() { return endedOn; }
    public String getProjectUrl() { return projectUrl; }
}
