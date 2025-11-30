package org.example.service;

import org.example.dto.ChatGPTRequest;
import org.example.dto.ChatGPTResponse;
import org.example.dto.PromptDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ChatGPTService {

    private final WebClient webClient;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model}")
    private String model;

    public ChatGPTService(WebClient webClient) {
        this.webClient = webClient;
    }

    public String getChatResponse(PromptDTO userInput) {
        ChatGPTRequest request = new ChatGPTRequest(
                model,
                List.of(new ChatGPTRequest.Message("user", userInput.prompt()))
        );

        ChatGPTResponse response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatGPTResponse.class)
                .block(); // блокування для синхронного виклику

        return response.choices().get(0).message().content();
    }
}
