package com.parul.llmgateway;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        long start = System.currentTimeMillis();

        AskService.AskResult result = askService.ask(request.question());

        long timeTakenMs = System.currentTimeMillis() - start;
        return new AskResponse(request.question(), result.answer(), result.source(), timeTakenMs);
    }

    public record AskResponse(String question, String answer, String source, long timeTakenMs) {}
}