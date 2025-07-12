package com.example.kafkaproxyapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

@SpringBootApplication
@RestController
@RequestMapping("/api/events")
public class KafkaProxyApplication {

	private static final Logger logger = Logger.getLogger(KafkaProxyApplication.class.getName());

	private final String port;
	private final String kafkaBrokers;
	private Producer<String, String> producer;
	private final List<Consumer<String, String>> consumers = new ArrayList<>();
	private final ExecutorService executorService = Executors.newFixedThreadPool(3);
	private final ObjectMapper objectMapper = new ObjectMapper();

	public KafkaProxyApplication() {
		this.port = System.getenv().getOrDefault("PORT", "8082");
		this.kafkaBrokers = System.getenv().getOrDefault("KAFKA_BROKERS", "localhost:9092");
	}

	public static void main(String[] args) {
		System.setProperty("server.port", System.getenv().getOrDefault("PORT", "8082"));
		SpringApplication.run(KafkaProxyApplication.class, args);
	}

	@PostConstruct
	public void init() {
		try {
			// Создание продюсера Kafka
			Properties producerProps = new Properties();
			producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
			producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
			producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

			producer = new KafkaProducer<>(producerProps);

			// Запуск консьюмеров
			executorService.submit(() -> consume("movie-events"));
			executorService.submit(() -> consume("user-events"));
			executorService.submit(() -> consume("payment-events"));

			logger.info("Starting proxy microservice on port " + port);

		} catch (Exception e) {
			logger.severe("Failed to initialize Kafka: " + e.getMessage());
			System.exit(1);
		}
	}

	@PreDestroy
	public void cleanup() {
		if (producer != null) {
			producer.close();
		}

		for (Consumer<String, String> consumer : consumers) {
			consumer.close();
		}

		executorService.shutdown();
	}

	private void consume(String topic) {
		Properties consumerProps = new Properties();
		consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
		consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "proxy-service-group");
		consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

		Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
		consumers.add(consumer);

		try {
			consumer.subscribe(Collections.singletonList(topic));
			logger.info("Started consuming messages from topic: " + topic);

			while (true) {
				ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

				for (ConsumerRecord<String, String> record : records) {
					logger.info("Received message from topic " + topic + ": " + record.value());
				}
			}
		} catch (Exception e) {
			logger.severe("Error consuming from topic " + topic + ": " + e.getMessage());
		}
	}

	private void produce(String topic) throws Exception {
		Map<String, String> data = new HashMap<>();
		data.put("topic", topic);
		data.put("time", LocalDateTime.now().toString());

		String dataJSON = objectMapper.writeValueAsString(data);

		ProducerRecord<String, String> record = new ProducerRecord<>(topic, dataJSON);

		try {
			RecordMetadata metadata = producer.send(record).get();
			logger.info("Topic: " + topic + " sent to partition " + metadata.partition() + " at offset " + metadata.offset());
		} catch (Exception e) {
			logger.severe("Failed to send message to topic " + topic + ": " + e.getMessage());
			throw e;
		}
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, Boolean>> handleHealth() {
		Map<String, Boolean> response = new HashMap<>();
		response.put("status", true);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/movie")
	public ResponseEntity<Map<String, String>> handleMovie() {
		try {
			produce("movie-events");
			Map<String, String> response = new HashMap<>();
			response.put("status", "success");
			return ResponseEntity.status(HttpStatus.CREATED).body(response);
		} catch (Exception e) {
			logger.severe("Error producing movie event: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	@PostMapping("/user")
	public ResponseEntity<Map<String, String>> handleUser() {
		try {
			produce("user-events");
			Map<String, String> response = new HashMap<>();
			response.put("status", "success");
			return ResponseEntity.status(HttpStatus.CREATED).body(response);
		} catch (Exception e) {
			logger.severe("Error producing user event: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	@PostMapping("/payment")
	public ResponseEntity<Map<String, String>> handlePayment() {
		try {
			produce("payment-events");
			Map<String, String> response = new HashMap<>();
			response.put("status", "success");
			return ResponseEntity.status(HttpStatus.CREATED).body(response);
		} catch (Exception e) {
			logger.severe("Error producing payment event: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}
}
