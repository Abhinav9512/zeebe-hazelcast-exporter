package io.zeebe.hazelcast.exporter;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;
import io.zeebe.exporter.proto.RecordTransformer;
import io.zeebe.exporter.proto.Schema;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class HazelcastExporter implements Exporter {

  private ExporterConfiguration config;
  private Logger logger;
  private Controller controller;

  private HazelcastInstance hazelcast;

  private Map<Integer, Ringbuffer<byte[]>> ringbufferMap = new HashMap<>();

  private Function<Record, byte[]> recordTransformer;

  @Override
  public void configure(Context context) {
    logger = context.getLogger();
    config = context.getConfiguration().instantiate(ExporterConfiguration.class);

    logger.info("Starting exporter with configuration: {}", config);

    final var filter = new HazelcastRecordFilter(config);
    context.setFilter(filter);

    configureFormat();
  }

  private void configureFormat() {
    final var format = config.getFormat();
    if (format.equalsIgnoreCase("protobuf")) {
      recordTransformer = this::recordToProtobuf;

    } else if (format.equalsIgnoreCase("json")) {
      recordTransformer = this::recordToJson;

    } else {
      throw new IllegalArgumentException(
              String.format(
                      "Expected the parameter 'format' to be one fo 'protobuf' or 'json' but was '%s'",
                      format));
    }
  }

  @Override
  public void open(Controller controller) {
    this.controller = controller;

    hazelcast =
            config
                    .getRemoteAddress()
                    .map(this::connectToHazelcast)
                    .orElseGet(this::createHazelcastInstance);
  }

  private HazelcastInstance createHazelcastInstance() {
    final var port = this.config.getPort();
    final var clusterName = this.config.getClusterName();

    final var hzConfig = new Config();
    hzConfig.getNetworkConfig().setPort(port);
    hzConfig.setProperty("hazelcast.logging.type", "slf4j");

    hzConfig.setClusterName(clusterName);

    final var ringbufferConfig = new RingbufferConfig(this.config.getName());

    if (this.config.getCapacity() > 0) {
      ringbufferConfig.setCapacity(this.config.getCapacity());
    }
    if (this.config.getTimeToLiveInSeconds() > 0) {
      ringbufferConfig.setTimeToLiveSeconds(this.config.getTimeToLiveInSeconds());
    }

    hzConfig.addRingBufferConfig(ringbufferConfig);

    logger.info(
            "Creating new in-memory Hazelcast instance [port: {}, cluster-name: {}]",
            port,
            clusterName);
    return Hazelcast.newHazelcastInstance(hzConfig);
  }

  private HazelcastInstance connectToHazelcast(String remoteAddress) {
    final var clusterName = this.config.getClusterName();

    final var clientConfig = new ClientConfig();
    clientConfig.setProperty("hazelcast.logging.type", "slf4j");
    clientConfig.setClusterName(clusterName);

    final var networkConfig = clientConfig.getNetworkConfig();
    networkConfig.addAddress(remoteAddress);

    final var connectionRetryConfig =
            clientConfig.getConnectionStrategyConfig().getConnectionRetryConfig();
    final var connectionTimeout = Duration.parse(this.config.getRemoteConnectionTimeout());
    connectionRetryConfig.setClusterConnectTimeoutMillis(connectionTimeout.toMillis());

    logger.info(
            "Connecting to remote Hazelcast instance [address: {}, cluster-name: {}]",
            remoteAddress,
            clusterName);

    return HazelcastClient.newHazelcastClient(clientConfig);
  }

  @Override
  public void close() {
    hazelcast.shutdown();
  }

  @Override
  public void export(Record record) {

    if (ringbufferMap.get(record.getPartitionId()) != null) {
      final byte[] transformedRecord = recordTransformer.apply(record);

      final var sequenceNumber = ringbufferMap.get(record.getPartitionId()).add(transformedRecord);
      logger.debug(
              "Added a record to the ring-buffer : {} [record-position: {}, ring-buffer sequence-number: {}]",
              ringbufferMap.get(record.getPartitionId()).getName(),
              record.getPosition(),
              sequenceNumber);
    } else {
      String rbName = config.getName() + "_" + record.getPartitionId();
      Ringbuffer<byte[]> ringbuffer = hazelcast.getRingbuffer(rbName);
      logger.info(
              "The code is Creating a new ring-buffer with name '{}' [head: {}, tail: {}, size: {}, capacity: {}] " +
                      "for partition id : {}",
              ringbuffer.getName(),
              ringbuffer.headSequence(),
              ringbuffer.tailSequence(),
              ringbuffer.size(),
              ringbuffer.capacity(),
              record.getPartitionId());
      ringbufferMap.put(record.getPartitionId(), ringbuffer);
    }

    controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private byte[] recordToProtobuf(Record record) {
    final Schema.Record dto = RecordTransformer.toGenericRecord(record);
    return dto.toByteArray();
  }

  private byte[] recordToJson(Record record) {
    final var json = record.toJson();
    return json.getBytes();
  }
}
