package com.hotel.notification;

/** A fully rendered e-mail (HTML + plain-text alternative). */
public record EmailMessage(String to, String subject, String htmlBody, String textBody) {}
