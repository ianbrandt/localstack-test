package com.ianbrandt.test.localstack;

import cloud.localstack.DockerTestUtils;
import cloud.localstack.docker.LocalstackDockerExtension;
import cloud.localstack.docker.annotation.LocalstackDockerProperties;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.CreateFunctionResult;
import com.amazonaws.services.lambda.model.FunctionCode;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.ArchivePaths;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.amazonaws.services.lambda.model.Runtime.Java8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
@ExtendWith(LocalstackDockerExtension.class)
@LocalstackDockerProperties(randomizePorts = true, services = {"s3", "lambda"})
class LocalStackLambdaZipDeploymentIT {

	private static final int HTTP_OK = 200;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private AmazonS3 amazonS3;
	private AWSLambda awsLambda;

	@BeforeAll
	void setUp() {

		amazonS3 = DockerTestUtils.getClientS3();
		awsLambda = DockerTestUtils.getClientLambda();
	}

	@Test
	void testSameModuleInputRequestHandler() throws IOException {

		// RequestHandler under test
		final Class<? extends RequestHandler> handlerClass = SameModuleInputRequestHandler.class;
		final String handlerClassName = handlerClass.getCanonicalName();
		final String lambdaFunctionName = handlerClass.getSimpleName();

		// Create Lambda archive
		final JavaArchive lambdaZip = ShrinkWrap.create(JavaArchive.class);
		lambdaZip.addClasses(handlerClass, SameModuleInput.class);
		System.out.println(lambdaZip.toString(true));

		// Create request object
		final SameModuleInput sameModuleInput = new SameModuleInput();
		sameModuleInput.setTestProperty("Testing");

		// Invoke Lambda
		final InvokeResult result = invokeLambda(lambdaZip, sameModuleInput, lambdaFunctionName, handlerClassName);

		// Assert post-conditions
		assertThat(result.getStatusCode()).isEqualTo(HTTP_OK);
	}

	@Test
	void testOtherModuleInputRequestHandler() throws IOException {

		// RequestHandler under test
		final Class<? extends RequestHandler> handlerClass = OtherModuleInputRequestHandler.class;
		final String handlerClassName = handlerClass.getCanonicalName();
		final String lambdaFunctionName = handlerClass.getSimpleName();

		// Create Lambda archive
		final JavaArchive lambdaZip = ShrinkWrap.create(JavaArchive.class);
		lambdaZip.addClasses(handlerClass);
		final ArchivePath archiveLibraryPath = ArchivePaths.create("/lib");
		Maven.resolver()
			.loadPomFromFile("pom.xml")
			.importCompileAndRuntimeDependencies()
			.resolve()
			.withTransitivity()
			.asList(JavaArchive.class)
			.forEach(javaArchive -> lambdaZip.add(javaArchive, archiveLibraryPath, ZipExporter.class));
		System.out.println(lambdaZip.toString(true));

		// Create request object
		final OtherModuleInput otherModuleInput = new OtherModuleInput();
		otherModuleInput.setOtherTestProperty("Testing");

		// Invoke Lambda
		final InvokeResult result = invokeLambda(lambdaZip, otherModuleInput, lambdaFunctionName, handlerClassName);

		// Assert post-conditions
		assertThat(result.getStatusCode()).isEqualTo(HTTP_OK);
	}

	private InvokeResult invokeLambda(final Archive lambdaArchive, final Object requestObject,
		final String lambdaFunctionName, final String handlerClassName) throws IOException {

		// Create temp file for archive
		final File tempLambdaZipFile = writeArchiveAsTempFile(lambdaArchive, "lambda-", ".zip");

		// Create S3 bucket for archive
		final String bucketName = "test-bucket";
		final Bucket s3Bucket = createTestS3Bucket(bucketName);
		assertThat(s3Bucket.getName()).isEqualTo(bucketName);

		// Upload archive to S3
		final String lambdaZipFileName = "testing.zip";
		final PutObjectResult putObjectResult = uploadLambdaFunction(bucketName, tempLambdaZipFile, lambdaZipFileName);
		assertThat(putObjectResult.getContentMd5()).isNotNull();

		// Create Lambda Function
		final CreateFunctionResult createFunctionResult = createLambdaFunction(bucketName, lambdaZipFileName,
			lambdaFunctionName, handlerClassName);
		assertThat(createFunctionResult.getFunctionArn()).isNotNull();

		// Create Lambda invocation request
		final InvokeRequest request = createLambdaInvokeRequest(requestObject, lambdaFunctionName);

		// Invoke Lambda
		return awsLambda.invoke(request);
	}

	private File writeArchiveAsTempFile(final Archive archive, final String prefix, final String suffix)
		throws IOException {

		// Create a temp file
		final Path tempPath = Files.createTempFile(prefix, suffix);
		final File tempFile = tempPath.toFile();

		// Write the archive to the temp file
		final ZipExporter zipExporter = archive.as(ZipExporter.class);
		final boolean writeToExistingTempFile = true;
		zipExporter.exportTo(tempFile, writeToExistingTempFile);

		return tempFile;
	}

	private Bucket createTestS3Bucket(final String bucketName) {

		return amazonS3.createBucket(bucketName);
	}

	private PutObjectResult uploadLambdaFunction(final String bucketName, final File file, final String fileName) {

		return amazonS3.putObject(bucketName, fileName, file);
	}

	private CreateFunctionResult createLambdaFunction(final String bucketName, final String lambdaZipFileName,
		final String lambdaFunctionName, final String handlerClassName) {

		final FunctionCode functionCode = new FunctionCode()
			.withS3Bucket(bucketName)
			.withS3Key(lambdaZipFileName);

		final CreateFunctionRequest createFunctionRequest = new CreateFunctionRequest()
			.withFunctionName(lambdaFunctionName)
			.withRuntime(Java8)
			.withHandler(handlerClassName)
			.withCode(functionCode)
			.withDescription("Test Lambda Function")
			.withTimeout(15)
			.withMemorySize(128)
			.withPublish(true);

		return awsLambda.createFunction(createFunctionRequest);
	}

	private InvokeRequest createLambdaInvokeRequest(final Object eventRequest, final String lambdaFunctionName)
		throws JsonProcessingException {

		final String eventRequestJson = objectMapper.writeValueAsString(eventRequest);

		return new InvokeRequest()
			.withFunctionName(lambdaFunctionName)
			.withPayload(eventRequestJson);
	}
}
