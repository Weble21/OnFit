package com.doggeon.jobrecommendation.recommendation;

import static java.util.Map.entry;

import java.util.Map;

/**
 * Reduces a free-text location to its top-level region, so "서울특별시 강남구" matches a "서울" posting.
 */
public final class RegionNormalizer {
    // "광주시" is intentionally absent: it is also a city in 경기도.
    private static final Map<String, String> FULL_NAMES = Map.ofEntries(
            entry("서울특별시", "서울"), entry("서울시", "서울"),
            entry("부산광역시", "부산"), entry("부산시", "부산"),
            entry("대구광역시", "대구"), entry("대구시", "대구"),
            entry("인천광역시", "인천"), entry("인천시", "인천"),
            entry("광주광역시", "광주"),
            entry("대전광역시", "대전"), entry("대전시", "대전"),
            entry("울산광역시", "울산"), entry("울산시", "울산"),
            entry("세종특별자치시", "세종"), entry("세종시", "세종"),
            entry("경기도", "경기"),
            entry("강원도", "강원"), entry("강원특별자치도", "강원"),
            entry("충청북도", "충북"), entry("충청남도", "충남"),
            entry("전라북도", "전북"), entry("전북특별자치도", "전북"),
            entry("전라남도", "전남"),
            entry("경상북도", "경북"), entry("경상남도", "경남"),
            entry("제주특별자치도", "제주"), entry("제주도", "제주"), entry("제주시", "제주")
    );

    private RegionNormalizer() {
    }

    public static String normalize(String value) {
        String region = value.trim().split("\\s+")[0];
        return FULL_NAMES.getOrDefault(region, region);
    }
}
