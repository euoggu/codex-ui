package com.example.codexui.codex.service;

import com.example.codexui.codex.config.CodexStorageProperties;
import com.example.codexui.codex.model.CodexMessageSearchMatchDto;
import com.example.codexui.codex.model.CodexMessageSearchResponse;
import com.example.codexui.codex.model.CodexMessageSearchSessionDto;
import com.example.codexui.codex.repo.CodexUiOverlayRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CodexSearchService {

    private static final int MAX_SCAN_SESSIONS = 500;
    private static final int MAX_SESSION_RESULTS = 120;
    private static final int MAX_MATCHES_PER_SESSION = 20;

    private final CodexStorageProperties properties;
    private final CodexUiOverlayRepository overlayRepository;
    private final ObjectMapper objectMapper;

    public CodexSearchService(CodexStorageProperties properties,
                              CodexUiOverlayRepository overlayRepository,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.overlayRepository = overlayRepository;
        this.objectMapper = objectMapper;
    }

    public CodexMessageSearchResponse searchMessages(String query, Boolean archived) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            CodexMessageSearchResponse empty = new CodexMessageSearchResponse();
            empty.setQuery("");
            empty.setCount(0);
            return empty;
        }

        Path stateDb = requireStateDb();
        List<ThreadMeta> candidates = loadThreadCandidates(stateDb);
        List<String> ids = new ArrayList<String>(candidates.size());
        for (ThreadMeta candidate : candidates) {
            ids.add(candidate.id);
        }

        Map<String, CodexUiOverlayRepository.SessionOverride> overrides = overlayRepository.findSessionOverrides(ids);
        List<CodexMessageSearchSessionDto> out = new ArrayList<CodexMessageSearchSessionDto>();
        String queryLower = normalized.toLowerCase();

        for (ThreadMeta candidate : candidates) {
            CodexUiOverlayRepository.SessionOverride override = overrides.get(candidate.id);
            if (override != null) {
                if (override.titleOverride != null && !override.titleOverride.trim().isEmpty()) {
                    candidate.title = override.titleOverride.trim();
                }
                candidate.folderId = override.folderId;
                if (override.archivedOverride != null) {
                    candidate.archived = override.archivedOverride.intValue() == 1;
                }
            }

            if (archived != null && archived.booleanValue() != candidate.archived) {
                continue;
            }

            CodexMessageSearchSessionDto result = searchInRollout(candidate, queryLower);
            if (result != null) {
                out.add(result);
                if (out.size() >= MAX_SESSION_RESULTS) {
                    break;
                }
            }
        }

        CodexMessageSearchResponse response = new CodexMessageSearchResponse();
        response.setQuery(normalized);
        response.setCount(out.size());
        response.setItems(out);
        return response;
    }

    private List<ThreadMeta> loadThreadCandidates(Path stateDb) {
        String sql = "select id, rollout_path, cwd, title, updated_at, archived from threads " +
                "order by updated_at desc limit ?";
        List<ThreadMeta> out = new ArrayList<ThreadMeta>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MAX_SCAN_SESSIONS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ThreadMeta meta = new ThreadMeta();
                    meta.id = rs.getString("id");
                    meta.rolloutPath = rs.getString("rollout_path");
                    meta.cwd = rs.getString("cwd");
                    meta.title = rs.getString("title");
                    meta.updatedAt = formatTimestamp(rs.getLong("updated_at"));
                    meta.archived = rs.getInt("archived") == 1;
                    out.add(meta);
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 Codex 搜索索引失败", ex);
        }
    }

    private CodexMessageSearchSessionDto searchInRollout(ThreadMeta candidate, String queryLower) {
        Path rolloutPath = Paths.get(candidate.rolloutPath);
        if (!Files.exists(rolloutPath)) {
            return null;
        }

        List<CodexMessageSearchMatchDto> matches = new ArrayList<CodexMessageSearchMatchDto>();
        int totalMatches = 0;
        int lineNumber = 0;

        try (BufferedReader reader = Files.newBufferedReader(rolloutPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                JsonNode root = objectMapper.readTree(line);
                JsonNode payload = root.path("payload");
                if (!"response_item".equals(root.path("type").asText()) ||
                        !"message".equals(payload.path("type").asText())) {
                    continue;
                }

                String role = payload.path("role").asText();
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }

                String content = extractText(payload.path("content"));
                if (content.isEmpty() || content.toLowerCase().indexOf(queryLower) < 0) {
                    continue;
                }

                totalMatches++;
                if (matches.size() < MAX_MATCHES_PER_SESSION) {
                    CodexMessageSearchMatchDto match = new CodexMessageSearchMatchDto();
                    match.setMessageId(candidate.id + "-line-" + lineNumber);
                    match.setRole(role);
                    match.setTimestamp(root.path("timestamp").asText());
                    match.setContent(content);
                    match.setSnippet(buildSnippet(content, queryLower));
                    matches.add(match);
                }
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "搜索 session 消息失败: " + rolloutPath, ex);
        }

        if (totalMatches <= 0) {
            return null;
        }

        CodexMessageSearchSessionDto dto = new CodexMessageSearchSessionDto();
        dto.setSessionId(candidate.id);
        dto.setTitle(candidate.title == null || candidate.title.trim().isEmpty() ? candidate.id : candidate.title);
        dto.setCwd(candidate.cwd);
        dto.setFolderId(candidate.folderId);
        dto.setUpdatedAt(candidate.updatedAt);
        dto.setMatchCount(totalMatches);
        dto.setMatches(matches);
        return dto;
    }

    private String buildSnippet(String content, String queryLower) {
        String normalized = content.replace('\n', ' ').trim();
        String lower = normalized.toLowerCase();
        int idx = lower.indexOf(queryLower);
        if (idx < 0) {
            return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
        }
        int start = Math.max(0, idx - 40);
        int end = Math.min(normalized.length(), idx + queryLower.length() + 80);
        String snippet = normalized.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < normalized.length()) {
            snippet = snippet + "...";
        }
        return snippet;
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

    private Path requireStateDb() {
        Path stateDb = properties.getStateDbPath();
        if (!Files.exists(stateDb)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到 Codex 本地索引库: " + stateDb);
        }
        return stateDb;
    }

    private String formatTimestamp(long rawValue) {
        if (rawValue <= 0L) {
            return null;
        }
        Instant instant = rawValue >= 1000000000000L
                ? Instant.ofEpochMilli(rawValue)
                : Instant.ofEpochSecond(rawValue);
        return instant.toString();
    }

    private static class ThreadMeta {
        private String id;
        private String rolloutPath;
        private String cwd;
        private String title;
        private String folderId;
        private String updatedAt;
        private boolean archived;
    }
}
