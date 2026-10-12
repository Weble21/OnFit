package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donggeon.jobrecommendation.ingestion.Work24Collector;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Work24CollectorTests {
    private static final String LIST = """
            <wantedRoot><total>1</total><wanted><wantedAuthNo>K123</wantedAuthNo>
            <region>서울</region><closeDt>20261031</closeDt></wanted></wantedRoot>
            """;
    private static final String DETAIL = """
            <wantedDtl><wantedAuthNo>K123</wantedAuthNo>
            <corpInfo><corpNm>테스트 기업</corpNm><indTpCdNm>IT</indTpCdNm></corpInfo>
            <wantedInfo><wantedTitle>백엔드 개발자</wantedTitle><jobsNm>소프트웨어 개발</jobsNm>
            <jobCont>API 개발</jobCont><workRegion>서울 강남구</workRegion></wantedInfo></wantedDtl>
            """;

    @Test void mapsOfficialFieldsWithoutInventingSkillsAndUsesCanonicalLink() throws Exception {
        var list = Work24Collector.parse(LIST, "wantedRoot");
        var detail = Work24Collector.parse(DETAIL, "wantedDtl");
        var wanted = (org.w3c.dom.Element) list.getElementsByTagName("wanted").item(0);
        var request = Work24Collector.map(wanted, detail, Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(request.externalId()).isEqualTo("K123");
        assertThat(request.sourceUrl()).contains("wantedAuthNo=K123").contains("infoTypeCd=VALIDATION");
        assertThat(request.content().deadline()).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(request.content().requiredSkills()).isEmpty();
        assertThat(request.content().preferredSkills()).isEmpty();
    }

    @Test void refusesEntityExpansionAndMismatchedDetail() throws Exception {
        assertThatThrownBy(() -> Work24Collector.parse(
                "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><wantedRoot>&e;</wantedRoot>", "wantedRoot"))
                .isInstanceOf(Exception.class);
        var list = Work24Collector.parse(LIST, "wantedRoot");
        var wanted = (org.w3c.dom.Element) list.getElementsByTagName("wanted").item(0);
        var detail = Work24Collector.parse(DETAIL.replace("K123", "OTHER"), "wantedDtl");
        assertThatThrownBy(() -> Work24Collector.map(wanted, detail, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
