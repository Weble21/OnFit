package com.doggeon.jobrecommendation.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ElementCollection
    @CollectionTable(name = "profile_target_roles", joinColumns = @JoinColumn(name = "profile_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "role_name", nullable = false, length = 120)
    private List<String> targetRoles = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "profile_preferred_locations", joinColumns = @JoinColumn(name = "profile_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "location_name", nullable = false, length = 120)
    private List<String> preferredLocations = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Skill> skills = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Certificate> certificates = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Project> projects = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Experience> experiences = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
    }

    public UserProfile(User user) {
        this.user = user;
    }

    public void addSkill(Skill skill) {
        skills.add(skill);
        skill.setProfile(this);
    }

    public void addCertificate(Certificate certificate) {
        certificates.add(certificate);
        certificate.setProfile(this);
    }

    public void addProject(Project project) {
        projects.add(project);
        project.setProfile(this);
    }

    public void addExperience(Experience experience) {
        experiences.add(experience);
        experience.setProfile(this);
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public List<String> getTargetRoles() { return targetRoles; }
    public List<String> getPreferredLocations() { return preferredLocations; }
    public List<Skill> getSkills() { return skills; }
    public List<Certificate> getCertificates() { return certificates; }
    public List<Project> getProjects() { return projects; }
    public List<Experience> getExperiences() { return experiences; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
