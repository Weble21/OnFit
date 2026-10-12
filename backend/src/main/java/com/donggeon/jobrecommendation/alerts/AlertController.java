package com.donggeon.jobrecommendation.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }
    public record Settings(boolean enabled,
            @NotNull @Size(max=20) List<@NotBlank @Size(max=180) String> companies,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minScore) { }
    public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=320) String email) { }
    public record Code(@NotBlank @Pattern(regexp="[0-9]{6}") String code) { }
    @GetMapping("/api/alerts/me") public Map<String,Object> get() { return alerts.mine(); }
    @PutMapping("/api/alerts/me") public Map<String,Object> save(@Valid @RequestBody Settings settings) {
        alerts.save(settings); return alerts.mine();
    }
    @PostMapping("/api/alerts/me/email") public Map<String,Object> email(@Valid @RequestBody Email email) {
        alerts.requestVerification(email.email()); return Map.of("sent", true);
    }
    @PostMapping("/api/alerts/me/verify") public Map<String,Object> verify(@Valid @RequestBody Code code) {
        if (!alerts.verify(code.code())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "인증번호가 틀렸거나 만료되었습니다. 다시 발급받아 주세요.");
        return alerts.mine();
    }
    @PostMapping("/api/admin/alerts/run") public Map<String,Object> run(
            @RequestParam(required=false) java.time.Instant before) { return alerts.run(before); }
    @GetMapping("/api/admin/alerts/targets") public List<String> targets() { return alerts.targets(); }
}
