package software.amazon.lambda.powertools.sqs.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.stream.Stream;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.util.StringInputStream;
import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import software.amazon.lambda.powertools.sqs.handlers.LambdaHandlerApiGateway;
import software.amazon.lambda.powertools.sqs.handlers.SqsMessageHandler;

import static com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.reflect.FieldUtils.writeStaticField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static software.amazon.lambda.powertools.sqs.internal.SqsMessageAspect.FailedProcessingLargePayloadException;

public class SqsMessageAspectTest {

    private RequestHandler<SQSEvent, String> requestHandler;

    @Mock
    private Context context;

    @Mock
    private AmazonS3 amazonS3;

    @BeforeEach
    void setUp() throws IllegalAccessException {
        initMocks(this);
        setupContext();
        writeStaticField(SqsMessageAspect.class, "amazonS3", amazonS3, true);
        requestHandler = new SqsMessageHandler();
    }

    @Test
    public void testLargeMessage() {
        String bucketName = "ms-extended-sqs-client";
        String bucketKey = "c71eb2ae-37e0-4265-8909-32f4153faddf";
        S3Object s3Response = new S3Object();
        s3Response.setObjectContent(new ByteArrayInputStream("A big message".getBytes()));

        when(amazonS3.getObject(bucketName, bucketKey)).thenReturn(s3Response);
        SQSEvent sqsEvent = messageWithBody("[\"software.amazon.payloadoffloading.PayloadS3Pointer\",{\"s3BucketName\":\"" + bucketName + "\",\"s3Key\":\"" + bucketKey + "\"}]");

        String response = requestHandler.handleRequest(sqsEvent, context);

        assertThat(response)
                .isEqualTo("A big message");

        verify(amazonS3).deleteObject(bucketName, bucketKey);
    }

    @Test
    public void shouldNotProcessSmallMessageBody() {
        String bucketName = "ms-extended-sqs-client";
        String bucketKey = "c71eb2ae-37e0-4265-8909-32f4153faddf";
        S3Object s3Response = new S3Object();
        s3Response.setObjectContent(new ByteArrayInputStream("A big message".getBytes()));

        when(amazonS3.getObject(bucketName, bucketKey)).thenReturn(s3Response);
        SQSEvent sqsEvent = messageWithBody("This is small message");

        String response = requestHandler.handleRequest(sqsEvent, context);

        assertThat(response)
                .isEqualTo("This is small message");

        verifyNoInteractions(amazonS3);
    }

    @ParameterizedTest
    @MethodSource("exception")
    public void shouldFailEntireBatchIfFailedDownloadingFromS3(RuntimeException exception) {
        String bucketName = "ms-extended-sqs-client";
        String bucketKey = "c71eb2ae-37e0-4265-8909-32f4153faddf";

        when(amazonS3.getObject(bucketName, bucketKey)).thenThrow(exception);

        String messageBody = "[\"software.amazon.payloadoffloading.PayloadS3Pointer\",{\"s3BucketName\":\"" + bucketName + "\",\"s3Key\":\"" + bucketKey + "\"}]";
        SQSEvent sqsEvent = messageWithBody(messageBody);

        assertThatExceptionOfType(FailedProcessingLargePayloadException.class)
                .isThrownBy(() -> requestHandler.handleRequest(sqsEvent, context))
                .withCause(exception);

        verify(amazonS3, never()).deleteObject(bucketName, bucketKey);
    }

    @Test
    public void shouldFailEntireBatchIfFailedProcessingDownloadMessageFromS3() throws IOException {
        String bucketName = "ms-extended-sqs-client";
        String bucketKey = "c71eb2ae-37e0-4265-8909-32f4153faddf";
        S3Object s3Response = new S3Object();

        s3Response.setObjectContent(new S3ObjectInputStream(new StringInputStream("test") {
            @Override
            public void close() throws IOException {
                throw new IOException("Failed");
            }
        }, mock(HttpRequestBase.class)));

        when(amazonS3.getObject(bucketName, bucketKey)).thenReturn(s3Response);

        String messageBody = "[\"software.amazon.payloadoffloading.PayloadS3Pointer\",{\"s3BucketName\":\"" + bucketName + "\",\"s3Key\":\"" + bucketKey + "\"}]";
        SQSEvent sqsEvent = messageWithBody(messageBody);

        assertThatExceptionOfType(FailedProcessingLargePayloadException.class)
                .isThrownBy(() -> requestHandler.handleRequest(sqsEvent, context))
                .withCauseInstanceOf(IOException.class);

        verify(amazonS3, never()).deleteObject(bucketName, bucketKey);
    }

    @Test
    public void shouldNotDoAnyProcessingWhenNotSqsEvent() {
        String bucketName = "ms-extended-sqs-client";
        String bucketKey = "c71eb2ae-37e0-4265-8909-32f4153faddf";
        LambdaHandlerApiGateway handler = new LambdaHandlerApiGateway();

        String messageBody = "[\"software.amazon.payloadoffloading.PayloadS3Pointer\",{\"s3BucketName\":\"" + bucketName + "\",\"s3Key\":\"" + bucketKey + "\"}]";

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(messageBody);
        String response = handler.handleRequest(event, context);

        assertThat(response)
                .isEqualTo(messageBody);

        verifyNoInteractions(amazonS3);
    }

    private static Stream<Arguments> exception() {
        return Stream.of(Arguments.of(new AmazonServiceException("Service Exception")),
                Arguments.of(new SdkClientException("Client Exception")));
    }

    private SQSEvent messageWithBody(String messageBody) {
        SQSMessage sqsMessage = new SQSMessage();
        sqsMessage.setBody(messageBody);
        SQSEvent sqsEvent = new SQSEvent();
        sqsEvent.setRecords(singletonList(sqsMessage));
        return sqsEvent;
    }

    private void setupContext() {
        when(context.getFunctionName()).thenReturn("testFunction");
        when(context.getInvokedFunctionArn()).thenReturn("testArn");
        when(context.getFunctionVersion()).thenReturn("1");
        when(context.getMemoryLimitInMB()).thenReturn(10);
    }
}