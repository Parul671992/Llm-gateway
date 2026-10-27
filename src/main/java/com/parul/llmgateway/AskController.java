package com.parul.llmgateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final GroqClient groqClient;

    public AskController(GroqClient groqClient) {
        this.groqClient = groqClient;
    }

    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String question) {
        long start = System.currentTimeMillis();

        String answer = groqClient.ask(question);

        long timeTakenMs = System.currentTimeMillis() - start;
        return new AskResponse(question, answer, "llm", timeTakenMs);
    }

    public record AskResponse(String question, String answer, String source, long timeTakenMs) {}
}