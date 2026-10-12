package com.donggeon.jobrecommendation.alerts;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class AlertMail {
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean enabled;
    public AlertMail(ObjectProvider<JavaMailSender> sender,
            @Value("${onfit.alerts.mail-from:}") String from,
            @Value("${onfit.alerts.enabled:false}") boolean enabled) {
        this.sender = sender; this.from = from; this.enabled = enabled;
    }
    public boolean available() { return enabled && !from.isBlank() && sender.getIfAvailable() != null; }
    public void send(String to, String subject, String body) {
        if (!available()) throw new IllegalStateException("Email delivery is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(to); message.setSubject(subject); message.setText(body);
        sender.getObject().send(message);
    }
}
