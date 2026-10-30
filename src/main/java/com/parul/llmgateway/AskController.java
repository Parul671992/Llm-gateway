package com.parul.llmgateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String question) {
        long start = System.currentTimeMillis();

        AskService.AskResult result = askService.ask(question);

        long timeTakenMs = System.currentTimeMillis() - start;
        return new AskResponse(question, result.answer(), result.source(), timeTakenMs);
    }

    public record AskResponse(String question, String answer, String source, long timeTakenMs) {}
}