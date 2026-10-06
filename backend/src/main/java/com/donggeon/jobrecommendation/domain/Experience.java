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
@Table(name = "experiences")
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "role_name", nullable = false, length = 200)
    private String roleName;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    protected Experience() {
    }

    public Experience(String companyName, String roleName, String description,
                      LocalDate startedOn, LocalDate endedOn) {
        this.companyName = companyName;
        this.roleName = roleName;
        this.description = description;
        this.startedOn = startedOn;
        this.endedOn = endedOn;
    }

    void setProfile(UserProfile profile) { this.profile = profile; }

    public Long getId() { return id; }
    public UserProfile getProfile() { return profile; }
    public String getCompanyName() { return companyName; }
    public String getRoleName() { return roleName; }
    public String getDescription() { return description; }
    public LocalDate getStartedOn() { return startedOn; }
    public LocalDate getEndedOn() { return endedOn; }
}
