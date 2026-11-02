package com.parul.llmgateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "question must not be empty")
        @Size(max = 2000, message = "question must be at most 2000 characters")
        String question
) {}