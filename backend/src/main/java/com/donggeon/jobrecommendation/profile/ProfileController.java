package com.donggeon.jobrecommendation.profile;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    // 계정 생성 - POST 요청
    @PostMapping
    public ResponseEntity<ProfileResponse> create(@Valid @RequestBody ProfileRequest request) {
        return ResponseEntity.created(URI.create("/api/profiles/me"))
                .body(service.create(request));
    }

    // 기존 내 프로필 조회 - GET 요청
    @GetMapping("/me")
    public ProfileResponse getMine() {
        return service.getMine();
    }

    // 프로필 업데이트- PUT 요청
    @PutMapping("/me")
    public ProfileResponse updateMine(@Valid @RequestBody ProfileRequest request) {
        return service.updateMine(request);
    }
}
