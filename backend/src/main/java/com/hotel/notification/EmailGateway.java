package com.hotel.notification;

/** Transport for outgoing e-mail. The only production implementation is AWS SES ({@code ses.SesEmailGateway}). */
public interface EmailGateway {

    /**
     * Sends one e-mail. Implementations may throw; {@link NotificationService} catches everything so a
     * mail problem can never fail a booking.
     */
    void send(String from, String to, String subject, String htmlBody, String textBody);
}
