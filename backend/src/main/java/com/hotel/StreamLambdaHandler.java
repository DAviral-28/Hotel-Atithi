package com.hotel;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lambda request stream handler powered by AWS Serverless Java Container.
 */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> HANDLER;

    static {
        try {
            HANDLER = SpringBootLambdaContainerHandler.getAwsProxyHandler(HotelApplication.class);
        } catch (ContainerInitializationException e) {
            // Re-throw to halt Lambda initialization if Spring fails to start
            throw new RuntimeException("Could not initialize Spring Boot application for Lambda", e);
        }
    }

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        HANDLER.proxyStream(inputStream, outputStream, context);
    }
}
