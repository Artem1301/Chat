package org.example.service;

import org.example.dto.ChatGPTRequest;
import org.example.dto.ChatGPTResponse;
import org.example.dto.FunctionCallRequest;
import org.example.entity.User;
import org.example.repository.UserRepository;
import org.example.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatDBService {

    private final WebClient webClient;
    private final UserRepository userRepository;
    private final SQLService sqlService;

    @Value("${openai.api.model}")
    private String model;

    public ChatDBService(WebClient openAiWebClient,
                         UserRepository userRepository,
                         SQLService sqlService) {
        this.webClient = openAiWebClient;
        this.userRepository = userRepository;
        this.sqlService = sqlService;
    }

    /**
     * Основний метод:
     * 1) Відправляємо system + user (питання) в GPT, вказуємо функцію runQuery у functions
     * 2) Якщо GPT повертає function_call -> беремо SQL з arguments, виконуємо
     * 3) Відправляємо другий запит до GPT з результатом SQL (role=user/system) щоб GPT сформулював відповідь
     */
    public String askAboutUsers(String question) {
        // 1) первинний виклик GPT, вимагаємо function_call
        String systemPrompt = """
                Ти — SQL-помічник, який вміє повертати SQL у вигляді виклику функції runQuery.
                Використовуй ЛИШЕ таблицю 'users' з колонками: id, name, age.
                Поверни тільки виклик функції (function_call) з полем arguments.query з SQL запитом.
                Якщо запит некоректний або небезпечний — повертай function_call з пустим або з безпечним SQL.
                Не вигадуй даних.
                """;

        ChatGPTRequest.FunctionDef runQueryFunction = new ChatGPTRequest.FunctionDef(
                "runQuery",
                "Виконати SQL та повернути результат",
                List.of(new ChatGPTRequest.FunctionParam("query", "string", "SQL query to execute (SELECT only)"))
        );

        ChatGPTRequest initial = new ChatGPTRequest(
                model,
                List.of(
                        new ChatGPTRequest.Message("system", systemPrompt),
                        new ChatGPTRequest.Message("user", question)
                ),
                List.of(runQueryFunction),
                0.0
        );

        String initReqJson = JsonUtils.toJson(initial);

        String initResp = webClient.post()
                .bodyValue(initReqJson)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (initResp == null) return "No response from OpenAI";

        ChatGPTResponse parsedInit = JsonUtils.fromJson(initResp, ChatGPTResponse.class);

        if (parsedInit.choices() == null || parsedInit.choices().isEmpty()) {
            return "No choices from OpenAI";
        }

        var choice = parsedInit.choices().get(0);
        var msg = choice.message();

        // If GPT returned a function call -> execute
        if (msg != null && msg.functionCall() != null) {
            Map<String, Object> args = msg.functionCall().arguments();
            Object queryObj = args.get("query");

            if (queryObj == null) {
                return "OpenAI returned function_call without 'query' argument.";
            }

            String sql = queryObj.toString().trim();

            // Basic safety: allow only SELECT
            String lowered = sql.toLowerCase();
            if (!lowered.startsWith("select")) {
                return "Only SELECT queries are allowed.";
            }

            List<Object[]> rows;
            try {
                rows = sqlService.executeQuery(sql);
            } catch (Exception e) {
                return "SQL execution error: " + e.getMessage();
            }

            String formatted = SQLResultFormatter.formatAsText(rows);

            // Send result back to GPT for final natural language answer
            ChatGPTRequest followUp = new ChatGPTRequest(
                    model,
                    List.of(
                            new ChatGPTRequest.Message("system", "Ти — допоміжний агент. Використовуй надані результати SQL, щоб відповісти користувачу. Не вигадуй більше даних."),
                            new ChatGPTRequest.Message("user", "Питання: " + question),
                            new ChatGPTRequest.Message("assistant", "Результат SQL:\n" + formatted)
                    ),
                    null,
                    0.0
            );

            String followJson = JsonUtils.toJson(followUp);
            String followResp = webClient.post()
                    .bodyValue(followJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (followResp == null) return "No follow-up response from OpenAI";

            ChatGPTResponse parsedFollow = JsonUtils.fromJson(followResp, ChatGPTResponse.class);
            if (parsedFollow.choices() == null || parsedFollow.choices().isEmpty()) {
                return "No choices in follow-up";
            }
            var finalMessage = parsedFollow.choices().get(0).message();
            return Optional.ofNullable(finalMessage.content()).orElse("No content");
        } else {
            // If no function call, return the content directly
            return Optional.ofNullable(msg == null ? null : msg.content()).orElse("No content in response");
        }
    }
}
