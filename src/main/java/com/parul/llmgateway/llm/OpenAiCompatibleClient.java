package com.parul.llmgateway.llm;

import java.net.http.HttpClient;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class OpenAiCompatibleClient {

    private final LlmProperties.Provider settings;
    private final RestClient restClient;

    public OpenAiCompatibleClient(LlmProperties.Provider settings) {
        this.settings = settings;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(settings.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(settings.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + settings.apiKey())
                .build();
    }

    public LlmResult ask(String question) {
        ChatRequest request = new ChatRequest(settings.model(),
                List.of(new Message("user", question)), settings.maxTokens());

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        Usage usage = response.usage() != null ? response.usage() : new Usage(0, 0);
        return new LlmResult(response.choices().get(0).message().content(),
                settings.name(), costMicros(usage));
    }

    public String name() { return settings.name(); }
    public String model() { return settings.model(); }

    private long costMicros(Usage usage) {
        long total = usage.promptTokens() * settings.inputMicrosPerMillion()
                + usage.completionTokens() * settings.outputMicrosPerMillion();
        return (total + 999_999) / 1_000_000;   // round up, never undercharge
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {}

    record ChatRequest(String model, List<Message> messages, @JsonProperty("max_tokens") int maxTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                 @JsonProperty("completion_tokens") int completionTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices, Usage usage) {}
}