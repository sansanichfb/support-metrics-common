/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.confluent.support.metrics;

import org.apache.avro.generic.GenericContainer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

import io.confluent.support.metrics.common.Collector;
import io.confluent.support.metrics.common.kafka.KafkaUtilities;
import io.confluent.support.metrics.common.kafka.ZkUtilsProvider;
import io.confluent.support.metrics.serde.AvroSerializer;
import io.confluent.support.metrics.submitters.ConfluentSubmitter;
import io.confluent.support.metrics.submitters.KafkaSubmitter;
import io.confluent.support.metrics.submitters.ResponseHandler;
import io.confluent.support.metrics.utils.Jitter;

/**
 * Periodically reports metrics collected from a Kafka broker.
 *
 * <p>Metrics are being reported to a Kafka topic within the same cluster and/or to Confluent via
 * the Internet.
 *
 * <p>This class is not thread-safe.
 */
public abstract class BaseMetricsReporter implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(BaseMetricsReporter.class);

  /**
   * Default "retention.ms" setting (i.e. time-based retention) of the support metrics topic. Used
   * when creating the topic in case it doesn't exist yet.
   */
  private static final long RETENTION_MS = 365 * 24 * 60 * 60 * 1000L;

  /**
   * Default replication factor of the support metrics topic. Used when creating the topic in case
   * it doesn't exist yet.
   */
  public static final int SUPPORT_TOPIC_REPLICATION = 3;

  /**
   * Default number of partitions of the support metrics topic. Used when creating the topic in case
   * it doesn't exist yet.
   */
  private static final int SUPPORT_TOPIC_PARTITIONS = 1;

  /**
   * Length of the wait period we give the server to start up completely (in a different thread)
   * before we begin metrics collection.
   */
  private static final long SETTLING_TIME_MS = 10 * 1000L;
  private final boolean enableSettlingTime;


  private String customerId;
  private long reportIntervalMs;
  private String supportTopic;
  private KafkaSubmitter kafkaSubmitter;
  private ConfluentSubmitter confluentSubmitter;
  private Collector metricsCollector;
  private final AvroSerializer encoder = new AvroSerializer();
  protected final KafkaUtilities kafkaUtilities;
  protected final BaseSupportConfig supportConfig;
  private final ResponseHandler responseHandler;

  public BaseMetricsReporter(BaseSupportConfig serverConfiguration) {
    this(serverConfiguration,  new KafkaUtilities(), null, true);
  }

  /**
   * @param supportConfig The properties this server was created from, plus extra Proactive Support
   *     (PS) one Note that Kafka does not understand PS properties, hence server->KafkaConfig()
   *     does not contain any of them, necessitating passing this extra argument to the API.
   * @param kafkaUtilities      An instance of {@link KafkaUtilities} that will be used to perform
   * @param responseHandler Http Response Handler
   * @param enableSettlingTime Enable settling time before starting metrics
   */
  public BaseMetricsReporter(
      BaseSupportConfig supportConfig,
      KafkaUtilities kafkaUtilities,
      ResponseHandler responseHandler,
      boolean enableSettlingTime
  ) {

    Objects.requireNonNull(supportConfig, "supportConfig can't be null");
    if (StringUtils.isNotBlank(supportConfig.getKafkaTopic())) {
      Objects.requireNonNull(kafkaUtilities, "kafkaUtilities can't be null");
    }
    this.kafkaUtilities = kafkaUtilities;
    this.supportConfig = supportConfig;
    this.responseHandler = responseHandler;
    this.enableSettlingTime = enableSettlingTime;
  }

  public void init() {
    customerId = supportConfig.getCustomerId();
    metricsCollector = metricsCollector();
    metricsCollector.setRuntimeState(Collector.RuntimeState.Running);

    reportIntervalMs = supportConfig.getReportIntervalMs();
    supportTopic = supportConfig.getKafkaTopic();

    if (!supportTopic.isEmpty()) {
      kafkaSubmitter = new KafkaSubmitter(zkUtilsProvider(), supportTopic);
    } else {
      kafkaSubmitter = null;
    }

    String endpointHTTP = supportConfig.getEndpointHTTP();
    String endpointHTTPS = supportConfig.getEndpointHTTPS();
    String proxyURI = supportConfig.getProxy();

    if (!endpointHTTP.isEmpty() || !endpointHTTPS.isEmpty()) {
      confluentSubmitter = new ConfluentSubmitter(customerId, endpointHTTP, endpointHTTPS,
          proxyURI, responseHandler
      );
    } else {
      confluentSubmitter = null;
    }

    if (!reportingEnabled()) {
      log.info("Metrics collection disabled by component configuration");
    }
  }

  protected abstract ZkUtilsProvider zkUtilsProvider();

  protected abstract Collector metricsCollector();

  protected boolean reportingEnabled() {
    return sendToKafkaEnabled() || sendToConfluentEnabled();
  }

  protected boolean sendToKafkaEnabled() {
    return kafkaSubmitter != null;
  }

  protected boolean sendToConfluentEnabled() {
    return confluentSubmitter != null;
  }

  @Override
  public void run() {
    try {
      if (reportingEnabled()) {
        boolean terminateEarly = waitForServer();
        if (terminateEarly) {
          log.info("Metrics collection stopped before it even started");
        } else {
          log.info("Starting metrics collection from monitored component...");
          while (!Thread.currentThread().isInterrupted()) {
            submitMetrics();
            Thread.sleep(Jitter.addOnePercentJitter(reportIntervalMs));

          }
        }
      }
    } catch (InterruptedException i) {
      metricsCollector.setRuntimeState(Collector.RuntimeState.ShuttingDown);
      submitMetrics();
      log.info("Graceful terminating metrics collection because the monitored component is "
               + "shutting down...");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("Terminating metrics collection from monitored component because: {}",
          e.getMessage());
    } finally {
      log.info("Metrics collection stopped");
    }
  }

  /**
   * Waits for the monitored service to fully start up.
   *
   * <p>This is a blocking call.  This method will return if and only if:
   *
   * <ul> <li>The service has successfully started.  The return value will be false.</li> <li>The
   * server is shutting down.  The return value will be true.</li> <li>The current thread was
   * interrupted.  The return value will be true.</li> </ul>
   */
  private boolean waitForServer() {
    boolean terminateEarly = false;
    try {
      boolean keepWaitingForServerToStartup = true;
      while (keepWaitingForServerToStartup && !Thread.currentThread().isInterrupted()) {

        if (enableSettlingTime) {
          long waitTimeMs = Jitter.addOnePercentJitter(SETTLING_TIME_MS);
          log.info("Waiting {} ms for the monitored service to finish starting up...", waitTimeMs);
          Thread.sleep(waitTimeMs);
        }

        if (isShuttingDown()) {
          keepWaitingForServerToStartup = false;
          terminateEarly = true;
          metricsCollector.setRuntimeState(Collector.RuntimeState.ShuttingDown);
          log.info("Stopping metrics collection prematurely because service is shutting down");
        } else {
          if (isReadyForMetricsCollection()) {
            log.info("Monitored service is now ready");
            keepWaitingForServerToStartup = false;
          }
        }
      }
    } catch (InterruptedException i) {
      terminateEarly = true;
      metricsCollector.setRuntimeState(Collector.RuntimeState.ShuttingDown);
      Thread.currentThread().interrupt();
    }
    return terminateEarly;
  }

  protected abstract boolean isReadyForMetricsCollection();

  protected abstract boolean isShuttingDown();

  // this is a protected method to enable testing
  protected void submitMetrics() {
    byte[] encodedMetricsRecord = null;
    GenericContainer metricsRecord = metricsCollector.collectMetrics();
    try {
      encodedMetricsRecord = encoder.serialize(metricsRecord);
    } catch (IOException e) {
      log.error("Could not serialize metrics record: {}", e.toString());
    }

    try {
      if (sendToKafkaEnabled() && encodedMetricsRecord != null) {
        // attempt to create the topic. If failures occur, try again in the next round, however
        // the current batch of metrics will be lost.
        if (kafkaUtilities.createAndVerifyTopic(zkUtilsProvider().zkUtils(), supportTopic,
            SUPPORT_TOPIC_PARTITIONS,
                                                SUPPORT_TOPIC_REPLICATION, RETENTION_MS
        )) {
          kafkaSubmitter.submit(encodedMetricsRecord);
        }
      }
    } catch (RuntimeException e) {
      log.error("Could not submit metrics to Kafka topic {}: {}", supportTopic, e.getMessage());
    }

    try {
      if (sendToConfluentEnabled() && encodedMetricsRecord != null) {
        confluentSubmitter.submit(encodedMetricsRecord);
      }
    } catch (RuntimeException e) {
      log.error("Could not submit metrics to Confluent: {}", e.getMessage());
    }
  }

}
