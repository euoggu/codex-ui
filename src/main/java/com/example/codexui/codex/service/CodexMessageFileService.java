package com.example.codexui.codex.service;

import com.example.codexui.app.config.CodexUiProperties;
import com.example.codexui.codex.config.CodexStorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodexMessageFileService {

    private final CodexSessionService codexSessionService;
    private final CodexUiProperties codexUiProperties;
    private final CodexStorageProperties codexStorageProperties;
    private final ObjectMapper objectMapper;

    public CodexMessageFileService(CodexSessionService codexSessionService,
                                   CodexUiProperties codexUiProperties,
                                   CodexStorageProperties codexStorageProperties,
                                   ObjectMapper objectMapper) {
        this.codexSessionService = codexSessionService;
        this.codexUiProperties = codexUiProperties;
        this.codexStorageProperties = codexStorageProperties;
        this.objectMapper = objectMapper;
    }

    public void updateMessage(String threadId, int lineNumber, String newContent) {
        String normalized = newContent == null ? "" : newContent.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 不能为空");
        }
        rewriteMessageLine(threadId, lineNumber, normalized, false);
    }

    public void deleteMessage(String threadId, int lineNumber) {
        rewriteMessageLine(threadId, lineNumber, null, true);
    }

    private void rewriteMessageLine(String threadId, int lineNumber, String newContent, boolean deleteLine) {
        Path rolloutPath = codexSessionService.getRolloutPath(threadId);
        List<String> lines;
        try {
            lines = Files.readAllLines(rolloutPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 session 文件失败: " + rolloutPath, e);
        }

        if (lineNumber <= 0 || lineNumber > lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息行号超出范围: " + lineNumber);
        }

        ThreadIndexMeta threadMeta = loadThreadIndexMeta(threadId);
        int firstPromptLine = findLineNumberByText(lines, threadMeta.firstUserMessage, "user");
        int index = lineNumber - 1;
        JsonNode root;
        try {
            root = objectMapper.readTree(lines.get(index));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析原始消息失败", e);
        }

        JsonNode payload = root.path("payload");
        if (!"response_item".equals(root.path("type").asText()) ||
                !"message".equals(payload.path("type").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标行不是可编辑消息");
        }

        String role = payload.path("role").asText();
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持编辑 user/assistant 消息");
        }
        int pairedEventMsgIndex = findPairedEventMsgIndex(lines, index, role);

        try {
            List<String> newLines = new ArrayList<String>(lines);
            if (deleteLine) {
                if (pairedEventMsgIndex >= 0 && pairedEventMsgIndex > index) {
                    newLines.remove(pairedEventMsgIndex);
                    newLines.remove(index);
                } else if (pairedEventMsgIndex >= 0) {
                    newLines.remove(index);
                    newLines.remove(pairedEventMsgIndex);
                } else {
                    newLines.remove(index);
                }
            } else {
                replaceContentText(root, newContent);
                newLines.set(index, objectMapper.writeValueAsString(root));
                if (pairedEventMsgIndex >= 0) {
                    JsonNode eventRoot = objectMapper.readTree(newLines.get(pairedEventMsgIndex));
                    replaceEventMessageText(eventRoot, newContent);
                    newLines.set(pairedEventMsgIndex, objectMapper.writeValueAsString(eventRoot));
                }
            }
            backupAndRewrite(rolloutPath, newLines);
            if (lineNumber == firstPromptLine) {
                syncFirstUserMessageIndexes(threadId, threadMeta, newLines, lineNumber, deleteLine, newContent);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写回 session 文件失败", e);
        }
    }

    private void replaceContentText(JsonNode root, String newContent) {
        JsonNode content = root.path("payload").path("content");
        if (!content.isArray() || content.size() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容结构不支持编辑");
        }

        boolean updated = false;
        for (JsonNode item : content) {
            if (item.isObject() && item.has("text")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("text", newContent);
                updated = true;
            }
        }

        if (!updated) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容结构不支持编辑");
        }
    }

    private void replaceEventMessageText(JsonNode root, String newContent) {
        JsonNode payload = root.path("payload");
        String type = payload.path("type").asText();
        if (!"event_msg".equals(root.path("type").asText()) ||
                (!"user_message".equals(type) && !"agent_message".equals(type))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event_msg 结构不支持编辑");
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("message", newContent);
    }

    private void backupAndRewrite(Path rolloutPath, List<String> newLines) throws IOException {
        Path backupDir = codexUiProperties.getHomePath().resolve("backups").resolve("sessions");
        Files.createDirectories(backupDir);

        String backupName = rolloutPath.getFileName().toString() + "." +
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now()) + ".bak";
        Files.copy(rolloutPath, backupDir.resolve(backupName), StandardCopyOption.REPLACE_EXISTING);

        Path tempPath = rolloutPath.resolveSibling(rolloutPath.getFileName().toString() + ".tmp");
        Files.write(tempPath, newLines, StandardCharsets.UTF_8);
        Files.move(tempPath, rolloutPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void syncFirstUserMessageIndexes(String threadId,
                                             ThreadIndexMeta threadMeta,
                                             List<String> newLines,
                                             int originalLineNumber,
                                             boolean deleteLine,
                                             String newContent) throws IOException {
        String nextFirstUserMessage;
        if (!deleteLine) {
            nextFirstUserMessage = newContent;
        } else {
            nextFirstUserMessage = findNextUserMessageText(newLines, Math.max(0, originalLineNumber - 1));
            if (nextFirstUserMessage == null) {
                nextFirstUserMessage = "";
            }
        }

        updateThreadsIndex(threadId, threadMeta, nextFirstUserMessage);
        updateHistory(threadId, nextFirstUserMessage);
    }

    private ThreadIndexMeta loadThreadIndexMeta(String threadId) {
        Path stateDb = codexStorageProperties.getStateDbPath();
        String sql = "select title, first_user_message from threads where id = ?";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到线程索引: " + threadId);
                }
                return new ThreadIndexMeta(rs.getString("title"), rs.getString("first_user_message"));
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 threads 索引失败", e);
        }
    }

    private void updateThreadsIndex(String threadId, ThreadIndexMeta meta, String nextFirstUserMessage) {
        Path stateDb = codexStorageProperties.getStateDbPath();
        String title = meta.title;
        String oldFirst = meta.firstUserMessage == null ? "" : meta.firstUserMessage;
        if (title == null || title.trim().isEmpty() || title.equals(oldFirst)) {
            title = nextFirstUserMessage;
        }

        String sql = "update threads set title = ?, first_user_message = ?, updated_at = ? where id = ?";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, nextFirstUserMessage);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.setString(4, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新 threads 索引失败", e);
        }
    }

    private void updateHistory(String threadId, String nextFirstUserMessage) throws IOException {
        Path historyPath = codexStorageProperties.getHomePath().resolve("history.jsonl");
        if (!Files.exists(historyPath)) {
            return;
        }

        List<String> lines = Files.readAllLines(historyPath, StandardCharsets.UTF_8);
        int firstMatch = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("\"session_id\":\"" + threadId + "\"")) {
                continue;
            }
            firstMatch = i;
            break;
        }

        if (firstMatch < 0) {
            return;
        }

        if (nextFirstUserMessage == null || nextFirstUserMessage.trim().isEmpty()) {
            lines.remove(firstMatch);
        } else {
            try {
                JsonNode node = objectMapper.readTree(lines.get(firstMatch));
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("text", nextFirstUserMessage);
                lines.set(firstMatch, objectMapper.writeValueAsString(node));
            } catch (IOException ex) {
                String line = lines.get(firstMatch);
                int textIndex = line.indexOf("\"text\":");
                if (textIndex < 0) {
                    throw ex;
                }
                String prefix = line.substring(0, textIndex + "\"text\":".length());
                String suffix = line.endsWith("}") ? "}" : "";
                lines.set(firstMatch, prefix + objectMapper.writeValueAsString(nextFirstUserMessage) + suffix);
            }
        }

        Path backupDir = codexUiProperties.getHomePath().resolve("backups").resolve("history");
        Files.createDirectories(backupDir);
        String backupName = "history.jsonl." +
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now()) + ".bak";
        Files.copy(historyPath, backupDir.resolve(backupName), StandardCopyOption.REPLACE_EXISTING);

        Path tempPath = historyPath.resolveSibling("history.jsonl.tmp");
        Files.write(tempPath, lines, StandardCharsets.UTF_8);
        Files.move(tempPath, historyPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private int findLineNumberByText(List<String> lines, String targetText, String role) {
        if (targetText == null || targetText.trim().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < lines.size(); i++) {
            try {
                JsonNode root = objectMapper.readTree(lines.get(i));
                JsonNode payload = root.path("payload");
                if (!"response_item".equals(root.path("type").asText()) ||
                        !"message".equals(payload.path("type").asText()) ||
                        !role.equals(payload.path("role").asText())) {
                    continue;
                }
                String content = extractText(payload.path("content"));
                if (targetText.equals(content)) {
                    return i + 1;
                }
            } catch (IOException ignored) {
            }
        }
        return -1;
    }

    private int findPairedEventMsgIndex(List<String> lines, int responseItemIndex, String role) {
        int candidate = "user".equals(role) ? responseItemIndex + 1 : responseItemIndex - 1;
        if (candidate < 0 || candidate >= lines.size()) {
            return -1;
        }
        try {
            JsonNode root = objectMapper.readTree(lines.get(candidate));
            JsonNode payload = root.path("payload");
            if (!"event_msg".equals(root.path("type").asText())) {
                return -1;
            }
            String type = payload.path("type").asText();
            if ("user".equals(role) && "user_message".equals(type)) {
                return candidate;
            }
            if ("assistant".equals(role) && "agent_message".equals(type)) {
                return candidate;
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    private String findNextUserMessageText(List<String> lines, int startIndexInclusive) {
        for (int i = Math.max(0, startIndexInclusive); i < lines.size(); i++) {
            try {
                JsonNode root = objectMapper.readTree(lines.get(i));
                JsonNode payload = root.path("payload");
                if (!"response_item".equals(root.path("type").asText()) ||
                        !"message".equals(payload.path("type").asText()) ||
                        !"user".equals(payload.path("role").asText())) {
                    continue;
                }
                String text = extractText(payload.path("content"));
                if (text != null && !text.trim().isEmpty()) {
                    return text;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private String extractText(JsonNode contentNode) {
        if (!contentNode.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (JsonNode item : contentNode) {
            String text = item.path("text").asText("");
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return String.join("\n", parts).trim();
    }

    private static class ThreadIndexMeta {
        private final String title;
        private final String firstUserMessage;

        private ThreadIndexMeta(String title, String firstUserMessage) {
            this.title = title;
            this.firstUserMessage = firstUserMessage;
        }
    }
}
