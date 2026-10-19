package com.donggeon.jobrecommendation.jobanalysis;

import java.util.List;

/**
 * Posting fields the AI service structured from confirmed text. Every value carries a quote from that
 * text; values the posting does not state are null or empty instead of being guessed.
 */
public record StructuredPosting(TextField companyName, TextField title, TextField roleName, TextField careerLevel,
                                TextField location, List<Item> responsibilities, List<Item> requiredSkills,
                                List<Item> preferredSkills, List<Item> qualifications, List<Ambiguity> ambiguities) {

    public record TextField(String value, String evidence) {
        static TextField orEmpty(TextField field) { return field == null ? new TextField(null, null) : field; }
    }

    public record Item(String value, String evidence) {}

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
        qualifications = copy(qualifications);
        ambiguities = copy(ambiguities);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
