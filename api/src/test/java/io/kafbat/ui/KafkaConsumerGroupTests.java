package io.kafbat.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.kafbat.ui.model.ConsumerGroupDTO;
import io.kafbat.ui.model.ConsumerGroupsPageResponseDTO;
import io.kafbat.ui.producer.KafkaTestProducer;
import java.io.Closeable;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.utils.Bytes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
class KafkaConsumerGroupTests extends AbstractIntegrationTest {
  @Autowired
  WebTestClient webTestClient;

  @Test
  void shouldNotFoundWhenNoSuchConsumerGroupId() {
    String groupId = "groupA";
    String topicName = "topicX";

    webTestClient
        .delete()
        .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}", LOCAL, groupId)
        .exchange()
        .expectStatus()
        .isNotFound();

    webTestClient
        .delete()
        .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}/topics/{topicName}", LOCAL, groupId, topicName)
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void shouldNotFoundWhenNoSuchTopic() {
    String topicName = createTopicWithRandomName();
    String topicNameUnSubscribed = "topicX";

    //Create a consumer and subscribe to the topic
    String groupId = UUID.randomUUID().toString();
    try (val consumer = createTestConsumerWithGroupId(groupId)) {
      consumer.subscribe(List.of(topicName));
      consumer.poll(Duration.ofMillis(100));

      webTestClient
          .delete()
          .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}/topics/{topicName}", LOCAL, groupId,
              topicNameUnSubscribed)
          .exchange()
          .expectStatus()
          .isNotFound();
    }
  }

  @Test
  void shouldOkWhenConsumerGroupIsNotActiveAndPartitionOffsetExists() {
    String topicName = createTopicWithRandomName();

    //Create a consumer and subscribe to the topic
    String groupId = UUID.randomUUID().toString();

    try (KafkaTestProducer<String, String> producer = KafkaTestProducer.forKafka(kafka)) {
      Flux.fromStream(
          Stream.of("one", "two", "three", "four")
              .map(value -> Mono.fromFuture(producer.send(topicName, value)))
      ).blockLast();
    } catch (Throwable e) {
      log.error("Error on sending", e);
      throw new RuntimeException(e);
    }

    try (val consumer = createTestConsumerWithGroupId(groupId)) {
      consumer.subscribe(List.of(topicName));
      consumer.poll(Duration.ofMillis(100));

      //Stop consumers to delete consumer offset from the topic
      consumer.pause(consumer.assignment());
    }

    //Delete the consumer offset when it's INACTIVE and check
    webTestClient
        .delete()
        .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}/topics/{topicName}", LOCAL, groupId, topicName)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void shouldOkWhenConsumerGroupIsNotActive() {
    String topicName = createTopicWithRandomName();

    //Create a consumer and subscribe to the topic
    String groupId = UUID.randomUUID().toString();
    try (val consumer = createTestConsumerWithGroupId(groupId)) {
      consumer.subscribe(List.of(topicName));
      consumer.poll(Duration.ofMillis(100));

      //Unsubscribe from all topics to be able to delete this consumer
      consumer.unsubscribe();
    }

    //Delete the consumer when it's INACTIVE and check
    webTestClient
        .delete()
        .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}", LOCAL, groupId)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void shouldBeBadRequestWhenConsumerGroupIsActive() {
    String topicName = createTopicWithRandomName();

    //Create a consumer and subscribe to the topic
    String groupId = UUID.randomUUID().toString();
    try (val consumer = createTestConsumerWithGroupId(groupId)) {
      consumer.subscribe(List.of(topicName));
      consumer.poll(Duration.ofMillis(100));

      //Try to delete the consumer when it's ACTIVE
      webTestClient
          .delete()
          .uri("/api/clusters/{clusterName}/consumer-groups/{groupId}", LOCAL, groupId)
          .exchange()
          .expectStatus()
          .isBadRequest();
    }
  }

  @Test
  void shouldReturnConsumerGroupsWithPagination() throws Exception {
    try (var ignored = startConsumerGroups(3, "cgPageTest1");
         var ignored1 = startConsumerGroups(2, "cgPageTest2")) {
      webTestClient
          .get()
          .uri("/api/clusters/{clusterName}/consumer-groups/paged?perPage=3&search=cgPageTest", LOCAL)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(ConsumerGroupsPageResponseDTO.class)
          .value(page -> {
            assertThat(page.getPageCount()).isEqualTo(2);
            assertThat(page.getConsumerGroups().size()).isEqualTo(3);
          });

      webTestClient
          .get()
          .uri("/api/clusters/{clusterName}/consumer-groups/paged?perPage=10&search=cgPageTest", LOCAL)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(ConsumerGroupsPageResponseDTO.class)
          .value(page -> {
            assertThat(page.getPageCount()).isEqualTo(1);
            assertThat(page.getConsumerGroups().size()).isEqualTo(5);
            assertThat(page.getConsumerGroups())
                .isSortedAccordingTo(Comparator.comparing(ConsumerGroupDTO::getGroupId));
          });

      webTestClient
          .get()
          .uri("/api/clusters/{clusterName}/consumer-groups/paged?perPage=10&&search"
              + "=cgPageTest&orderBy=NAME&sortOrder=DESC", LOCAL)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(ConsumerGroupsPageResponseDTO.class)
          .value(page -> {
            assertThat(page.getPageCount()).isEqualTo(1);
            assertThat(page.getConsumerGroups().size()).isEqualTo(5);
            assertThat(page.getConsumerGroups())
                .isSortedAccordingTo(Comparator.comparing(ConsumerGroupDTO::getGroupId).reversed());
          });

      webTestClient
          .get()
          .uri("/api/clusters/{clusterName}/consumer-groups/paged?perPage=10&&search"
              + "=cgPageTest&orderBy=MEMBERS&sortOrder=DESC", LOCAL)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(ConsumerGroupsPageResponseDTO.class)
          .value(page -> {
            assertThat(page.getPageCount()).isEqualTo(1);
            assertThat(page.getConsumerGroups().size()).isEqualTo(5);
            assertThat(page.getConsumerGroups())
                .isSortedAccordingTo(Comparator.comparing(ConsumerGroupDTO::getMembers).reversed());
          });
    }
  }

  private Closeable startConsumerGroups(int count, String consumerGroupPrefix) {
    String topicName = createTopicWithRandomName();
    var consumers =
        Stream.generate(() -> {
          String groupId = consumerGroupPrefix + RandomStringUtils.randomAlphabetic(5);
          val consumer = createTestConsumerWithGroupId(groupId);
          consumer.subscribe(List.of(topicName));
          consumer.poll(Duration.ofMillis(100));
          return consumer;
        })
        .limit(count)
        .toList();
    return () -> {
      consumers.forEach(KafkaConsumer::close);
      deleteTopic(topicName);
    };
  }

  private String createTopicWithRandomName() {
    String topicName = getClass().getSimpleName() + "-" + UUID.randomUUID();
    short replicationFactor = 1;
    int partitions = 1;
    createTopic(new NewTopic(topicName, partitions, replicationFactor));
    return topicName;
  }

  private KafkaConsumer<Bytes, Bytes> createTestConsumerWithGroupId(String groupId) {
    Properties props = new Properties();
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, groupId);
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }
}
