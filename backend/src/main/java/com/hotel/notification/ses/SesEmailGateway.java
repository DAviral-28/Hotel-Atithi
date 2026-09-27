package com.hotel.notification.ses;

import com.hotel.notification.EmailGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * AWS SES (v2 API) transport. The SES client is created lazily on the first send, so a deployment
 * without {@code AWS_SES_SENDER_EMAIL} never builds it and never needs SES permissions.
 */
@Component
public class SesEmailGateway implements EmailGateway {

    private final String region;
    private volatile SesV2Client client;

    public SesEmailGateway(@Value("${hotel.aws.region:ap-south-1}") String region) {
        this.region = region;
    }

    private SesV2Client client() {
        SesV2Client c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = SesV2Client.builder().region(Region.of(region)).build();
                    client = c;
                }
            }
        }
        return c;
    }

    @Override
    public void send(String from, String to, String subject, String htmlBody, String textBody) {
        Content subjectContent = Content.builder().data(subject).charset("UTF-8").build();
        Body body = Body.builder()
                .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                .text(Content.builder().data(textBody).charset("UTF-8").build())
                .build();
        client().sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(EmailContent.builder()
                        .simple(Message.builder().subject(subjectContent).body(body).build())
                        .build())
                .build());
    }
}
