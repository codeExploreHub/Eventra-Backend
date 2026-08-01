package com.sandeep.eventrabackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.frontend-url:https://eventra.sandeepvashishtha.in}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        String resetLink = frontendUrl + "/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Eventra - Password Reset Request");
        message.setText(
                "You requested a password reset.\n\n" +
                "Click the link below to reset your password:\n" +
                resetLink + "\n\n" +
                "This link will expire in 15 minutes.\n\n" +
                "If you did not request this, please ignore this email."
        );

        mailSender.send(message);
    }
}