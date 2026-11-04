package com.parul.llmgateway;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GroqClient {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public GroqClient(@Value("${groq.base-url}") String baseUrl,
                      @Value("${groq.api-key}") String apiKey,
                      @Value("${groq.model}") String model,
                      @Value("${groq.connect-timeout}") Duration connectTimeout,
                      @Value("${groq.read-timeout}") Duration readTimeout,
                      @Value("${groq.max-tokens}") int maxTokens) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public LlmResult ask(String question) {
        ChatRequest request = new ChatRequest(model, List.of(new Message("user", question)), maxTokens);

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        Usage usage = response.usage() != null ? response.usage() : new Usage(0, 0);
        return new LlmResult(response.choices().get(0).message().content(),
                usage.promptTokens(), usage.completionTokens());
    }

    public String model() {
        return model;
    }

    public record LlmResult(String content, int promptTokens, int completionTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}

    public record ChatRequest(String model, List<Message> messages,
                              @JsonProperty("max_tokens") int maxTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                        @JsonProperty("completion_tokens") int completionTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(List<Choice> choices, Usage usage) {}
}