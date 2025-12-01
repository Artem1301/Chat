package org.example.controller;

import org.example.service.ChatDBService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat-db")
@CrossOrigin("*")
public class ChatDBController {

    private final ChatDBService chatDBService;

    public ChatDBController(ChatDBService chatDBService) {
        this.chatDBService = chatDBService;
    }

    @PostMapping
    public String ask(@RequestBody String question) {
        return chatDBService.askAboutUsers(question);
    }
}
