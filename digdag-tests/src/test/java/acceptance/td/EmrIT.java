package acceptance.td;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduceClient;
import com.amazonaws.services.elasticmapreduce.model.Application;
import com.amazonaws.services.elasticmapreduce.model.JobFlowInstancesConfig;
import com.amazonaws.services.elasticmapreduce.model.RunJobFlowRequest;
import com.amazonaws.services.elasticmapreduce.model.RunJobFlowResult;
import com.amazonaws.services.elasticmapreduce.model.TerminateJobFlowsRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import io.digdag.client.DigdagClient;
import io.digdag.core.config.YamlConfigLoader;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.TemporaryDigdagServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static io.digdag.util.RetryExecutor.retryExecutor;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static utils.TestUtils.addResource;
import static utils.TestUtils.addWorkflow;
import static utils.TestUtils.attemptSuccess;
import static utils.TestUtils.createProject;
import static utils.TestUtils.expect;
import static utils.TestUtils.pushAndStart;
import static utils.TestUtils.pushProject;

public class EmrIT
{
    private static final Logger logger = LoggerFactory.getLogger(EmrIT.class);

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String S3_TEMP_BUCKET = System.getenv().getOrDefault("EMR_IT_S3_TEMP_BUCKET", "");

    private static final String AWS_ACCESS_KEY_ID = System.getenv().getOrDefault("EMR_IT_AWS_ACCESS_KEY_ID", "");
    private static final String AWS_SECRET_ACCESS_KEY = System.getenv().getOrDefault("EMR_IT_AWS_SECRET_ACCESS_KEY", "");
    private static final String AWS_ROLE = System.getenv().getOrDefault("EMR_IT_AWS_ROLE", "");

    protected String tmpS3FolderKey;
    protected AmazonS3URI tmpS3FolderUri;

    protected final List<String> clusterIds = new ArrayList<>();

    protected AmazonElasticMapReduceClient emr;
    protected AmazonS3 s3;

    protected TemporaryDigdagServer server;

    protected Path projectDir;
    protected String projectName;
    protected int projectId;

    protected Path outfile;

    protected DigdagClient digdagClient;

    @Before
    public void setUp()
            throws Exception
    {
        assumeThat(S3_TEMP_BUCKET, not(isEmptyOrNullString()));
        assumeThat(AWS_ACCESS_KEY_ID, not(isEmptyOrNullString()));
        assumeThat(AWS_SECRET_ACCESS_KEY, not(isEmptyOrNullString()));
        assumeThat(AWS_ROLE, not(isEmptyOrNullString()));

        AWSCredentials credentials = new BasicAWSCredentials(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY);

        // TODO: assume the supplied role?

        emr = new AmazonElasticMapReduceClient(credentials);
        s3 = new AmazonS3Client(credentials);

        server = TemporaryDigdagServer.builder()
                .withRandomSecretEncryptionKey()
                .build();
        server.start();

        projectDir = folder.getRoot().toPath();
        createProject(projectDir);
        projectName = projectDir.getFileName().toString();
        projectId = pushProject(server.endpoint(), projectDir, projectName);

        outfile = folder.newFolder().toPath().resolve("outfile");

        digdagClient = DigdagClient.builder()
                .host(server.host())
                .port(server.port())
                .build();

        digdagClient.setProjectSecret(projectId, "aws.emr.access-key-id", AWS_ACCESS_KEY_ID);
        digdagClient.setProjectSecret(projectId, "aws.emr.secret-access-key", AWS_SECRET_ACCESS_KEY);
        digdagClient.setProjectSecret(projectId, "aws.emr.role-arn", AWS_ROLE);

        addResource(projectDir, "acceptance/emr/bootstrap_foo.sh");
        addResource(projectDir, "acceptance/emr/bootstrap_hello.sh");
        addResource(projectDir, "acceptance/emr/WordCount.jar");
        addResource(projectDir, "acceptance/emr/libhello.jar");
        addResource(projectDir, "acceptance/emr/simple.jar");
        addResource(projectDir, "acceptance/emr/hello.py");
        addResource(projectDir, "acceptance/emr/hello.sh");
        addResource(projectDir, "acceptance/emr/query.sql");
        addResource(projectDir, "acceptance/emr/pi.scala");
        addResource(projectDir, "acceptance/emr/emr_configuration.json");
        addWorkflow(projectDir, "acceptance/emr/emr.dig");

        DateTimeFormatter f = DateTimeFormatter.ofPattern("YYYYMMdd_HHmmssSSS", Locale.ROOT).withZone(UTC);
        String now = f.format(Instant.now());
        tmpS3FolderKey = "tmp/" + now + "-" + UUID.randomUUID();
        tmpS3FolderUri = new AmazonS3URI("s3://" + S3_TEMP_BUCKET + "/" + tmpS3FolderKey);

        putS3(S3_TEMP_BUCKET, tmpS3FolderKey + "/applications/pi.py", "acceptance/emr/pi.py");
        putS3(S3_TEMP_BUCKET, tmpS3FolderKey + "/scripts/hello.sh", "acceptance/emr/hello.sh");
    }

    private void putS3(String bucket, String key, String resource)
            throws IOException
    {
        logger.info("put {} -> s3://{}/{}", resource, bucket, key);
        URL resourceUrl = Resources.getResource(resource);
        byte[] bytes = Resources.toByteArray(resourceUrl);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        s3.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
    }

    @After
    public void cleanUpS3()
            throws Exception
    {
        if (tmpS3FolderKey == null) {
            return;
        }

        ListObjectsRequest request = new ListObjectsRequest()
                .withBucketName(S3_TEMP_BUCKET)
                .withPrefix(tmpS3FolderKey);

        while (true) {
            ObjectListing listing = s3.listObjects(request);
            String[] keys = listing.getObjectSummaries().stream().map(S3ObjectSummary::getKey).toArray(String[]::new);
            for (String key : keys) {
                logger.info("delete s3://{}/{}", S3_TEMP_BUCKET, key);
            }
            retryExecutor()
                    .retryIf(e -> e instanceof AmazonServiceException)
                    .run(() -> s3.deleteObjects(new DeleteObjectsRequest(S3_TEMP_BUCKET).withKeys(keys)));
            if (listing.getNextMarker() == null) {
                break;
            }
        }
    }

    @Test
    public void foo()
            throws Exception
    {

    }

    @After
    public void tearDownDigdagServer()
            throws Exception
    {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @After
    public void tearDownEmrClusters()
            throws Exception
    {
        if (!clusterIds.isEmpty()) {
            emr.terminateJobFlows(new TerminateJobFlowsRequest().withJobFlowIds(clusterIds));
        }
    }

    public static class EmrWithExistingClusterTest
            extends EmrIT
    {
        @Test
        public void test()
                throws Exception
        {
            RunJobFlowRequest request = new RunJobFlowRequest()
                    .withName("Digdag Test")
                    .withReleaseLabel("emr-5.2.0")
                    .withApplications(Stream.of("Hadoop", "Hive", "Spark", "Flink")
                            .map(s -> new Application().withName(s))
                            .collect(toList()))
                    .withJobFlowRole("EMR_EC2_DefaultRole")
                    .withServiceRole("EMR_DefaultRole")
                    .withVisibleToAllUsers(true)
                    .withLogUri(tmpS3FolderUri + "/logs/")
                    .withInstances(new JobFlowInstancesConfig()
                            .withEc2KeyName("digdag-test")
                            .withInstanceCount(1)
                            .withKeepJobFlowAliveWhenNoSteps(true)
                            .withMasterInstanceType("m3.xlarge")
                            .withSlaveInstanceType("m3.xlarge"));

            RunJobFlowResult result = emr.runJobFlow(request);

            String clusterId = result.getJobFlowId();

            clusterIds.add(clusterId);

            long attemptId = pushAndStart(server.endpoint(), projectDir, "emr", ImmutableMap.of(
                    "test_s3_folder", tmpS3FolderKey,
                    "test_cluster", clusterId,
                    "outfile", outfile.toString()));
            expect(Duration.ofMinutes(30), attemptSuccess(server.endpoint(), attemptId));
            assertThat(Files.exists(outfile), is(true));
        }

        @Test
        public void manualTest()
                throws Exception
        {
            String clusterId = System.getenv("EMR_TEST_CLUSTER_ID");
            assumeThat(clusterId, not(Matchers.isEmptyOrNullString()));

            long attemptId = pushAndStart(server.endpoint(), projectDir, "emr", ImmutableMap.of(
                    "test_s3_folder", tmpS3FolderUri.toString(),
                    "test_cluster", clusterId,
                    "outfile", outfile.toString()));
            expect(Duration.ofMinutes(30), attemptSuccess(server.endpoint(), attemptId));
            assertThat(Files.exists(outfile), is(true));
        }
    }

    public static class EmrWithNewClusterTest
            extends EmrIT
    {
        @Test
        public void test()
                throws Exception
        {
            String cluster = new YamlConfigLoader().loadString(Resources.toString(Resources.getResource("acceptance/emr/cluster.yaml"), UTF_8)).toString();
            long attemptId = pushAndStart(server.endpoint(), projectDir, "emr", ImmutableMap.of(
                    "test_s3_folder", tmpS3FolderUri.toString(),
                    "test_cluster", cluster,
                    "outfile", outfile.toString()));
            expect(Duration.ofMinutes(30), attemptSuccess(server.endpoint(), attemptId));
            assertThat(Files.exists(outfile), is(true));
        }
    }
}