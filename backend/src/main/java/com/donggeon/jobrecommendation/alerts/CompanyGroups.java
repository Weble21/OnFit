package com.donggeon.jobrecommendation.alerts;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reviewed groups shared with the Python collector and frontend; no substring-based affiliation. */
@Component
public class CompanyGroups {
    private static final Pattern LEGAL_FORMS = Pattern.compile(
            "\\(\\s*(?:주|유|사|재|株)\\s*\\)|㈜|주식회사|유한책임회사|유한회사", Pattern.UNICODE_CHARACTER_CLASS);
    private final Map<String,List<String>> groups;
    public record Catalog(String reviewedOn, String sourceUrl, Map<String,List<String>> groups) { }
    public CompanyGroups(JsonMapper json) {
        try(var input=new ClassPathResource("company-groups.json").getInputStream()) {
            Catalog catalog=json.readValue(input,Catalog.class);
            Map<String,List<String>> indexed=new LinkedHashMap<>();
            catalog.groups().forEach((name,members)->indexed.put(key(name),List.copyOf(members)));
            groups=Map.copyOf(indexed);
        } catch(Exception failure) { throw new IllegalStateException("Cannot load reviewed company groups",failure); }
    }
    public static String name(String value) {
        return LEGAL_FORMS.matcher(value).replaceAll(" ").replaceAll("(?U)\\s+"," ").strip();
    }
    public static String key(String value) { return name(value).replace(" ","").toLowerCase(Locale.ROOT); }
    public List<String> expand(List<String> interests) {
        Map<String,String> result=new LinkedHashMap<>();
        for(String interest:interests) {
            String normalized=name(interest);
            if(normalized.isEmpty()) continue;
            for(String member:groups.getOrDefault(key(normalized),List.of(normalized)))
                result.putIfAbsent(key(member),name(member));
        }
        return List.copyOf(result.values());
    }
    public boolean matches(String company,List<String> interests) {
        String companyKey=key(company);
        return expand(interests).stream().anyMatch(member->key(member).equals(companyKey));
    }
}
