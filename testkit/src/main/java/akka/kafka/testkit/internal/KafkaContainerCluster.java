/*
 * Copyright (C) 2014 - 2016 Softwaremill <https://softwaremill.com>
 * Copyright (C) 2016 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.kafka.testkit.internal;

import akka.annotation.InternalApi;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;

/** Provides an easy way to launch a Kafka cluster with multiple brokers. */
@InternalApi
public class KafkaContainerCluster implements Startable {

  public static final String CONFLUENT_PLATFORM_VERSION =
      AlpakkaKafkaContainer.DEFAULT_CP_PLATFORM_VERSION;
  public static final int START_TIMEOUT_SECONDS = 120;

  private static final String READINESS_CHECK_SCRIPT = "/testcontainers_readiness_check.sh";
  private static final String READINESS_CHECK_TOPIC = "ready-kafka-container-cluster";
  private static final Version BOOTSTRAP_PARAM_MIN_VERSION = new Version("5.2.0");

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Version confluentPlatformVersion;
  private final int brokersNum;
  private final Network network;
  private final GenericContainer zookeeper;
  private final Collection<AlpakkaKafkaContainer> brokers;
  private final DockerClient dockerClient = DockerClientFactory.instance().client();

  public KafkaContainerCluster(int brokersNum, int internalTopicsRf) {
    this(CONFLUENT_PLATFORM_VERSION, brokersNum, internalTopicsRf);
  }

  public KafkaContainerCluster(
      String confluentPlatformVersion, int brokersNum, int internalTopicsRf) {
    if (brokersNum < 0) {
      throw new IllegalArgumentException("brokersNum '" + brokersNum + "' must be greater than 0");
    }
    if (internalTopicsRf < 0 || internalTopicsRf > brokersNum) {
      throw new IllegalArgumentException(
          "internalTopicsRf '"
              + internalTopicsRf
              + "' must be less than brokersNum and greater than 0");
    }

    this.confluentPlatformVersion = new Version(confluentPlatformVersion);
    this.brokersNum = brokersNum;
    this.network = Network.newNetwork();

    this.zookeeper =
        new GenericContainer("confluentinc/cp-zookeeper:" + confluentPlatformVersion)
            .withNetwork(network)
            .withNetworkAliases("zookeeper")
            .withEnv("ZOOKEEPER_CLIENT_PORT", String.valueOf(AlpakkaKafkaContainer.ZOOKEEPER_PORT));

    this.brokers =
        IntStream.range(0, this.brokersNum)
            .mapToObj(
                brokerNum ->
                    new AlpakkaKafkaContainer(confluentPlatformVersion)
                        .withNetwork(this.network)
                        .withNetworkAliases("broker-" + brokerNum)
                        .withRemoteJmxService()
                        .dependsOn(this.zookeeper)
                        .withExternalZookeeper("zookeeper:" + AlpakkaKafkaContainer.ZOOKEEPER_PORT)
                        .withEnv("KAFKA_BROKER_ID", brokerNum + "")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", internalTopicsRf + "")
                        .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", internalTopicsRf + "")
                        .withEnv(
                            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", internalTopicsRf + "")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", internalTopicsRf + ""))
            .collect(Collectors.toList());
  }

  public Network getNetwork() {
    return this.network;
  }

  public GenericContainer getZooKeeper() {
    return this.zookeeper;
  }

  public Collection<AlpakkaKafkaContainer> getBrokers() {
    return this.brokers;
  }

  public String getBootstrapServers() {
    return brokers.stream()
        .map(AlpakkaKafkaContainer::getBootstrapServers)
        .collect(Collectors.joining(","));
  }

  private Stream<GenericContainer> allContainers() {
    return Stream.concat(this.brokers.stream(), Stream.of(this.zookeeper));
  }

  @Override
  public void start() {
    try {
      Startables.deepStart(this.brokers.stream()).get(START_TIMEOUT_SECONDS, SECONDS);

      // assert that cluster has formed
      Unreliables.retryUntilTrue(
          START_TIMEOUT_SECONDS,
          TimeUnit.SECONDS,
          () ->
              Stream.of(this.zookeeper)
                  .map(this::clusterBrokers)
                  .anyMatch(brokers -> brokers.split(",").length == this.brokersNum));

      this.brokers.stream()
          .findFirst()
          .ifPresent(
              broker -> {
                broker.copyFileToContainer(
                    Transferable.of(readinessCheckScript().getBytes(StandardCharsets.UTF_8), 700),
                    READINESS_CHECK_SCRIPT);
              });

      // test produce & consume message with full cluster involvement
      Unreliables.retryUntilTrue(
          START_TIMEOUT_SECONDS,
          TimeUnit.SECONDS,
          () -> this.brokers.stream().findFirst().map(this::runReadinessCheck).orElse(false));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private String clusterBrokers(GenericContainer c) {
    try {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      dockerClient
          .execStartCmd(
              dockerClient
                  .execCreateCmd(c.getContainerId())
                  .withAttachStdout(true)
                  .withCmd(
                      "sh",
                      "-c",
                      "zookeeper-shell zookeeper:"
                          + AlpakkaKafkaContainer.ZOOKEEPER_PORT
                          + " ls /brokers/ids | tail -n 1")
                  .exec()
                  .getId())
          .exec(new ExecStartResultCallback(outputStream, null))
          .awaitCompletion();
      return outputStream.toString();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private String readinessCheckScript() {
    String connect = kafkaTopicConnectParam();
    String command = "#!/bin/bash \n";
    command += "set -e \n";
    command +=
        "kafka-topics "
            + connect
            + " --delete --topic "
            + READINESS_CHECK_TOPIC
            + " || echo \"topic does not exist\" \n";
    command +=
        "kafka-topics "
            + connect
            + " --topic "
            + READINESS_CHECK_TOPIC
            + " --create --partitions "
            + this.brokersNum
            + " --replication-factor "
            + this.brokersNum
            + " --config min.insync.replicas="
            + this.brokersNum
            + " \n";
    command += "MESSAGE=\"`date -u`\" \n";
    command +=
        "echo \"$MESSAGE\" | kafka-console-producer --broker-list localhost:9092 --topic "
            + READINESS_CHECK_TOPIC
            + " --producer-property acks=all \n";
    command +=
        "kafka-console-consumer --bootstrap-server localhost:9092 --topic "
            + READINESS_CHECK_TOPIC
            + " --from-beginning --timeout-ms 2000 --max-messages 1 | grep \"$MESSAGE\" \n";
    command += "kafka-topics " + connect + " --delete --topic " + READINESS_CHECK_TOPIC + " \n";
    command += "echo \"test succeeded\" \n";
    return command;
  }

  private String kafkaTopicConnectParam() {
    if (this.confluentPlatformVersion.compareTo(BOOTSTRAP_PARAM_MIN_VERSION) >= 0) {
      return "--bootstrap-server localhost:9092";
    } else {
      return "--zookeeper zookeeper:" + AlpakkaKafkaContainer.ZOOKEEPER_PORT;
    }
  }

  private Boolean runReadinessCheck(GenericContainer c) {
    try {
      ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
      ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
      dockerClient
          .execStartCmd(
              dockerClient
                  .execCreateCmd(c.getContainerId())
                  .withAttachStdout(true)
                  .withAttachStderr(true)
                  .withCmd("sh", "-c", READINESS_CHECK_SCRIPT)
                  .exec()
                  .getId())
          .exec(new ExecStartResultCallback(stdoutStream, stderrStream))
          .awaitCompletion();
      log.debug("Readiness check returned errors:\n{}", stderrStream.toString());
      return stdoutStream.toString().contains("test succeeded");
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void stop() {
    allContainers().parallel().forEach(GenericContainer::stop);
  }
}

@InternalApi
class Version implements Comparable<Version> {

  private String version;

  public final String get() {
    return this.version;
  }

  public Version(String version) {
    if (version == null) throw new IllegalArgumentException("Version can not be null");
    if (!version.matches("[0-9]+(\\.[0-9]+)*"))
      throw new IllegalArgumentException("Invalid version format");
    this.version = version;
  }

  @Override
  public int compareTo(Version that) {
    if (that == null) return 1;
    String[] thisParts = this.get().split("\\.");
    String[] thatParts = that.get().split("\\.");
    int length = Math.max(thisParts.length, thatParts.length);
    for (int i = 0; i < length; i++) {
      int thisPart = i < thisParts.length ? Integer.parseInt(thisParts[i]) : 0;
      int thatPart = i < thatParts.length ? Integer.parseInt(thatParts[i]) : 0;
      if (thisPart < thatPart) return -1;
      if (thisPart > thatPart) return 1;
    }
    return 0;
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) return true;
    if (that == null) return false;
    if (this.getClass() != that.getClass()) return false;
    return this.compareTo((Version) that) == 0;
  }
}
