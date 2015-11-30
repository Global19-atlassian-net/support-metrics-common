package io.confluent.support.metrics.common.kafka;

import org.apache.kafka.common.protocol.SecurityProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.server.RunningAsBroker;
import kafka.utils.CoreUtils;
import kafka.utils.SystemTime$;
import kafka.utils.TestUtils;
import kafka.zk.EmbeddedZookeeper;
import scala.Option;
import scala.Option$;

/**
 * Helper class for starting an embedded zookeeper and broker. This class is NOT thread safe.
 */
public class EmbeddedKafkaCluster {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedKafkaCluster.class);

  private static final Option<SecurityProtocol> INTER_BROKER_SECURITY_PROTOCOL = Option.apply(SecurityProtocol.PLAINTEXT);
  private static final boolean ENABLE_CONTROLLED_SHUTDOWN = false;
  private static final boolean ENABLE_DELETE_TOPIC = false;
  private static final int BROKER_PORT = 0;
  private static final Option<File> TRUST_STORE_FILE = Option$.MODULE$.<File>empty();
  private static final boolean ENABLE_PLAINTEXT = true;
  private static final boolean ENABLE_SASL_PLAINTEXT = false;
  private static final int SASL_PLAINTEXT_PORT = 0;
  private static final boolean ENABLE_SSL = false;
  private static final int SSL_PORT = 0;
  private static final boolean ENABLE_SASL_SSL = false;
  private static final int SASL_SSL_PORT = 0;

  private EmbeddedZookeeper zookeeper = null;
  private final Map<Integer, KafkaServer> brokersById = new ConcurrentHashMap<>();

  private void startZookeeperIfNeeded() {
    if (zookeeper == null) {
      zookeeper = new EmbeddedZookeeper();
    }
  }

  /**
   * Starts N=numBrokers Kafka brokers backed by a single-node ZK ensemble.
   *
   * Brokers are assigned consecutive ids beginning from zero up to numBrokers - 1.
   *
   * We do not guarantee a specific startup order, except that ZK instances will be started before
   * any broker instances.
   *
   * @param numBrokers Number of Kafka brokers to start (must be equal or larger than 1).
   */
  public void startCluster(int numBrokers) {
    if (numBrokers <= 0) {
      throw new IllegalArgumentException("number of brokers must be >= 1");
    }
    startZookeeperIfNeeded();
    for (int brokerId = 0; brokerId < numBrokers; brokerId++) {
      log.debug("Starting broker with id {} ...", brokerId);
      startBroker(brokerId);
    }
  }

  private void startBroker(int brokerId) {
    if (brokerId < 0) {
      throw new IllegalArgumentException("broker id must not be negative");
    }
    if (!brokersById.containsKey(brokerId)) {
      Properties props = TestUtils.createBrokerConfig(brokerId, zookeeperConnectString(),
          ENABLE_CONTROLLED_SHUTDOWN,
          ENABLE_DELETE_TOPIC,
          BROKER_PORT,
          INTER_BROKER_SECURITY_PROTOCOL,
          TRUST_STORE_FILE,
          ENABLE_PLAINTEXT,
          ENABLE_SASL_PLAINTEXT,
          SASL_PLAINTEXT_PORT,
          ENABLE_SSL,
          SSL_PORT,
          ENABLE_SASL_SSL,
          SASL_SSL_PORT);
      KafkaServer broker = TestUtils.createServer(KafkaConfig.fromProps(props), SystemTime$.MODULE$);
      brokersById.put(brokerId, broker);
    } else {
      KafkaServer broker = brokersById.get(brokerId);
      if (broker.brokerState().currentState() == RunningAsBroker.state()) {
        log.debug("Broker with id {} is already running", brokerId);
      } else {
        log.debug("Restarting broker with id {} ...", brokerId);
        stopBroker(brokerId);
        startBroker(brokerId);
        log.debug("Broker with id {} was restarted", brokerId);
      }
    }
  }

  /**
   * @return the broker with the given id, or null if no such broker exists.
   */
  public KafkaServer getBroker(int brokerId) {
    return brokersById.get(brokerId);
  }

  /**
   * Stops all Kafka brokers as well as the backing ZK ensemble.
   *
   * We do not guarantee a specific shutdown order, except that brokers will be shut down before any
   * ZK instances.
   */
  public void stopCluster() {
    for (int brokerId : brokersById.keySet()) {
      log.debug("Stopping broker with id {} ...", brokerId);
      stopBroker(brokerId);
    }
    stopZookeeper();
  }

  private void stopBroker(int brokerId) {
    if (brokersById.containsKey(brokerId)) {
      KafkaServer broker = brokersById.get(brokerId);
      broker.shutdown();
      broker.awaitShutdown();
      CoreUtils.rm(broker.config().logDirs());
      brokersById.remove(brokerId);
    }
  }

  private void stopZookeeper() {
    if (zookeeper == null) {
      return;
    }
    zookeeper.shutdown();
    zookeeper = null;
  }

  public String zookeeperConnectString() {
    if (zookeeper != null) {
      return "localhost:" + zookeeper.port();
    } else {
      throw new IllegalStateException("ZooKeeper instance has not been started yet -- did you actually start the cluster?");
    }
  }

}