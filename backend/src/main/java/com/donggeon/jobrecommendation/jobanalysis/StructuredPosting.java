package com.donggeon.jobrecommendation.jobanalysis;

import java.util.List;

/**
 * Posting fields the AI service structured from confirmed text. Every value carries a quote from that
 * text; values the posting does not state are null or empty instead of being guessed.
 */
public record StructuredPosting(TextField companyName, TextField title, TextField roleName, TextField careerLevel,
                                TextField location, List<Item> responsibilities, List<Item> requiredSkills,
                                List<Item> preferredSkills, List<Item> qualifications, List<Ambiguity> ambiguities, List<Item> dutySkills,
                                List<CertificateCondition> requiredCertificates, List<CertificateCondition> preferredCertificates,
                                List<Item> preferredCriteria) {

    public StructuredPosting(TextField companyName, TextField title, TextField roleName, TextField careerLevel,
                             TextField location, List<Item> responsibilities, List<Item> requiredSkills,
                             List<Item> preferredSkills, List<Item> qualifications, List<Ambiguity> ambiguities, List<Item> dutySkills,
                             List<CertificateCondition> requiredCertificates, List<CertificateCondition> preferredCertificates) {
        this(companyName, title, roleName, careerLevel, location, responsibilities, requiredSkills, preferredSkills,
                qualifications, ambiguities, dutySkills, requiredCertificates, preferredCertificates, List.of());
    }

    public StructuredPosting(TextField companyName, TextField title, TextField roleName, TextField careerLevel,
                             TextField location, List<Item> responsibilities, List<Item> requiredSkills,
                             List<Item> preferredSkills, List<Item> qualifications, List<Ambiguity> ambiguities, List<Item> dutySkills) {
        this(companyName, title, roleName, careerLevel, location, responsibilities, requiredSkills, preferredSkills,
                qualifications, ambiguities, dutySkills, List.of(), List.of());
    }

    public StructuredPosting(TextField companyName, TextField title, TextField roleName, TextField careerLevel,
                             TextField location, List<Item> responsibilities, List<Item> requiredSkills,
                             List<Item> preferredSkills, List<Item> qualifications, List<Ambiguity> ambiguities) {
        this(companyName, title, roleName, careerLevel, location, responsibilities, requiredSkills,
                preferredSkills, qualifications, ambiguities, List.of());
    }

    public record TextField(String value, String evidence) {
        static TextField orEmpty(TextField field) { return field == null ? new TextField(null, null) : field; }
    }

    public record Item(String value, String evidence) {}

    public record CertificateCondition(String value, String evidence, List<String> alternatives, boolean anyItCertificate) {
        public CertificateCondition { alternatives = copy(alternatives); }
    }

    public record Ambiguity(String text, String evidence) {}

    public StructuredPosting {
        companyName = TextField.orEmpty(companyName);
        title = TextField.orEmpty(title);
        roleName = TextField.orEmpty(roleName);
        careerLevel = TextField.orEmpty(careerLevel);
        location = TextField.orEmpty(location);
        responsibilities = copy(responsibilities);
        requiredSkills = copy(requiredSkills);
        preferredSkills = copy(preferredSkills);
        dutySkills = copy(dutySkills);
        qualifications = copy(qualifications);
        ambiguities = copy(ambiguities);
        requiredCertificates = copy(requiredCertificates);
        preferredCertificates = copy(preferredCertificates);
        preferredCriteria = copy(preferredCriteria);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
