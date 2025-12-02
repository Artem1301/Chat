package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.dto.ChatGPTRequest;
import org.example.dto.ChatGPTResponse;
import org.example.dto.PromptDTO;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.sql.*;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ChatGPTService {

    private final WebClient webClient;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model}")
    private String model;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPass;

    public ChatGPTService(WebClient webClient) {
        this.webClient = webClient;
    }

    // --------------------  JSON EXPORT FROM POSTGRES ---------------------

    public static String tableToJson(Connection conn, String tableName, String where)
            throws SQLException, JsonProcessingException {

        if (!tableName.matches("[a-zA-Z0-9_\\.]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }

        String sql = "SELECT * FROM " + tableName;
        if (where != null && !where.trim().isEmpty()) {
            sql += " WHERE " + where;
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int columns = md.getColumnCount();

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {

                    String colName = md.getColumnLabel(i);
                    Object val = rs.getObject(i);

                    if (val instanceof Timestamp ts) {
                        row.put(colName, ts.toInstant().atOffset(ZoneOffset.UTC).toString());
                    } else if (val instanceof Date d) {
                        row.put(colName, d.toString());
                    } else if (val instanceof Array arr) {
                        try {
                            row.put(colName, arr.getArray());
                        } finally {
                            try { arr.free(); } catch (Exception ignored) {}
                        }
                    } else if (val instanceof PGobject pg) {
                        if ("json".equals(pg.getType()) || "jsonb".equals(pg.getType())) {
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                row.put(colName, mapper.readValue(pg.getValue(), Object.class));
                            } catch (Exception e) {
                                row.put(colName, pg.getValue());
                            }
                        } else {
                            row.put(colName, pg.getValue());
                        }
                    } else {
                        row.put(colName, val);
                    }
                }
                rows.add(row);
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);

            return mapper.writeValueAsString(rows);
        }
    }

    private String loadTableJson() {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            return tableToJson(conn, "cars", null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------  MAIN CHATGPT LOGIC ---------------------

    public String getChatResponse(PromptDTO userInput) {

        // 1. Отримуємо JSON з таблиці
        String tableJson = loadTableJson();

        // 2. Формуємо фінальний промпт
        String finalPrompt =
                userInput.prompt()
                        + "\n\n---\nВикористовуй ці дані (JSON):\n"
                        + tableJson;

        // 3. Обмеження на кількість слів + відправка таблиці
        ChatGPTRequest request = new ChatGPTRequest(
                model,
                List.of(
                        new ChatGPTRequest.Message(
                                "system",
                                "Відповідай не більше ніж 100 слів, та не використовуй ці символи у відповіді (*/), відповідай наче ти консультант в автосалоні."
                        ),
                        new ChatGPTRequest.Message(
                                "user",
                                finalPrompt
                        )
                )
        );

        ChatGPTResponse response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatGPTResponse.class)
                .block();

        return response.choices().get(0).message().content();
    }
}
