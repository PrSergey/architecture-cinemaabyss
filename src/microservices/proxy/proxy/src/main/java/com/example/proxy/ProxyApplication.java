package com.example.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@SpringBootApplication
@RestController
public class ProxyApplication {

	private static final Logger logger = Logger.getLogger(ProxyApplication.class.getName());

	private final String monolithUrl;
	private final String moviesServiceUrl;
	private final boolean gradualMigration;
	private final int migrationPercent;
	private final RestTemplate restTemplate;

	private final AtomicInteger counterA = new AtomicInteger(0);
	private final AtomicInteger counterB = new AtomicInteger(0);

	public ProxyApplication() {
		// Получаем переменные окружения
		String port = System.getenv("PORT");
		logger.info("port " + port);
		if (port == null || port.isEmpty()) {
			System.setProperty("server.port", "8000");
		} else {
			System.setProperty("server.port", port);
		}

		this.gradualMigration = !"false".equals(System.getenv("GRADUAL_MIGRATION"));

		String percentStr = System.getenv("MOVIES_MIGRATION_PERCENT");
		int percent = 0;
		try {
			percent = Integer.parseInt(percentStr);
		} catch (NumberFormatException e) {
			logger.severe("Invalid MOVIES_MIGRATION_PERCENT: " + percentStr);
			System.exit(1);
		}

		if (percent <= 0 || percent > 100) {
			logger.severe("Invalid percent: " + percent);
			System.exit(1);
		}
		this.migrationPercent = percent;

		this.monolithUrl = System.getenv("MONOLITH_URL");
		if (monolithUrl == null || monolithUrl.isEmpty()) {
			logger.severe("MONOLITH_URL is not set");
			System.exit(1);
		}

		this.moviesServiceUrl = System.getenv("MOVIES_SERVICE_URL");
		if (moviesServiceUrl == null || moviesServiceUrl.isEmpty()) {
			logger.severe("MOVIES_SERVICE_URL is not set");
			System.exit(1);
		}

		this.restTemplate = new RestTemplate();

		logger.info("API Gateway started with configuration:");
		logger.info("Gradual migration: " + gradualMigration);
		logger.info("Migration percent: " + migrationPercent);
		logger.info("Monolith URL: " + monolithUrl);
		logger.info("Movies Service URL: " + moviesServiceUrl);
	}

	@RequestMapping("/**")
	public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException, URISyntaxException {
		logger.info("Request: " + request);
		String path = request.getRequestURI();
		String queryString = request.getQueryString();
		String fullPath = queryString != null ? path + "?" + queryString : path;

		boolean useMoviesService = false;

		if ("/api/movies".equals(path) && gradualMigration) {
			Random random = new Random();
			int ms = random.nextInt(101);
			if (ms < migrationPercent) {
				useMoviesService = true;
				int count = counterA.incrementAndGet();
				logger.info("Sent to Movies Service, counter: " + count);
			} else {
				int count = counterB.incrementAndGet();
				logger.info("Sent to Monolith, counter: " + count);
			}
		}

		String targetUrl = useMoviesService ? moviesServiceUrl : monolithUrl;
		proxyRequest(request, response, targetUrl + fullPath);
	}

	private void proxyRequest(HttpServletRequest request, HttpServletResponse response,
							  String targetUrl) throws IOException {
		try {
			// Копируем заголовки
			HttpHeaders headers = new HttpHeaders();
			Enumeration<String> headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				headers.add(headerName, request.getHeader(headerName));
			}

			// Создаем HttpEntity с телом запроса если есть
			HttpEntity<byte[]> entity;
			if (request.getContentLength() > 0) {
				byte[] body = request.getInputStream().readAllBytes();
				entity = new HttpEntity<>(body, headers);
			} else {
				entity = new HttpEntity<>(headers);
			}

			// Выполняем запрос
			ResponseEntity<byte[]> responseEntity = restTemplate.exchange(
					new URI(targetUrl),
					HttpMethod.valueOf(request.getMethod()),
					entity,
					byte[].class
			);

			// Копируем статус ответа
			response.setStatus(responseEntity.getStatusCodeValue());

			// Копируем заголовки ответа
			responseEntity.getHeaders().forEach((name, values) ->
					values.forEach(value -> response.addHeader(name, value))
			);

			// Копируем тело ответа
			if (responseEntity.getBody() != null) {
				response.getOutputStream().write(responseEntity.getBody());
			}

		} catch (Exception e) {
			logger.severe("Error proxying request: " + e.getMessage());
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Error proxying request");
		}
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

	public static void main(String[] args) {
		SpringApplication.run(ProxyApplication.class, args);
	}
}
