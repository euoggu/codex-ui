package com.example.codexui.codex.service;

import com.example.codexui.codex.config.CodexStorageProperties;
import com.example.codexui.codex.model.CodexMessageDto;
import com.example.codexui.codex.model.CodexSessionDetailDto;
import com.example.codexui.codex.model.CodexSessionListResponse;
import com.example.codexui.codex.model.CodexSessionSummaryDto;
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
public class CodexSessionService {

    private final CodexStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final CodexUiOverlayRepository overlayRepository;

    public CodexSessionService(CodexStorageProperties properties,
                               ObjectMapper objectMapper,
                               CodexUiOverlayRepository overlayRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.overlayRepository = overlayRepository;
    }

    public CodexSessionListResponse listSessions(String query, Boolean archived, Integer limit) {
        Path stateDb = requireStateDb();
        int actualLimit = normalizeLimit(limit);

        List<CodexSessionSummaryDto> items = new ArrayList<CodexSessionSummaryDto>();
        List<String> ids = new ArrayList<String>();
        int fetchLimit = Math.min(Math.max(actualLimit * 5, actualLimit + 100), 500);
        String sql = "select id, rollout_path, created_at, updated_at, source, model_provider, cwd, title, " +
                "archived, first_user_message " +
                "from threads " +
                "where (? = '' or lower(title) like ? or lower(cwd) like ? or lower(first_user_message) like ?) " +
                "order by updated_at desc limit ?";

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String likeQuery = "%" + normalizedQuery + "%";

        try (Connection connection = openConnection(stateDb);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedQuery);
            statement.setString(2, likeQuery);
            statement.setString(3, likeQuery);
            statement.setString(4, likeQuery);
            statement.setInt(5, fetchLimit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    CodexSessionSummaryDto summary = mapSummary(rs);
                    items.add(summary);
                    ids.add(summary.getId());
                }
            }
        } catch (SQLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 Codex session 索引失败", ex);
        }

        Map<String, CodexUiOverlayRepository.SessionOverride> overrides = overlayRepository.findSessionOverrides(ids);
        List<CodexSessionSummaryDto> merged = new ArrayList<CodexSessionSummaryDto>(items.size());
        for (CodexSessionSummaryDto item : items) {
            CodexUiOverlayRepository.SessionOverride o = overrides.get(item.getId());
            if (o != null) {
                if (o.titleOverride != null && !o.titleOverride.trim().isEmpty()) {
                    item.setTitle(o.titleOverride.trim());
                }
                item.setFolderId(o.folderId);
                if (o.archivedOverride != null) {
                    item.setArchived(o.archivedOverride.intValue() == 1);
                }
            }
            if (archived == null || archived.booleanValue() == item.isArchived()) {
                merged.add(item);
                if (merged.size() >= actualLimit) {
                    break;
                }
            }
        }

        CodexSessionListResponse response = new CodexSessionListResponse();
        response.setCodexHome(properties.getHomePath().toString());
        response.setLimit(actualLimit);
        response.setCount(merged.size());
        response.setItems(merged);
        return response;
    }

    public CodexSessionDetailDto getSession(String sessionId, boolean includeMessages, boolean includeNonChatRoles) {
        Path stateDb = requireStateDb();
        String sql = "select id, rollout_path, created_at, updated_at, source, model_provider, cwd, title, " +
                "archived, first_user_message, tokens_used from threads where id = ?";

        try (Connection connection = openConnection(stateDb);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到对应的 Codex session: " + sessionId);
                }

                CodexSessionDetailDto detail = new CodexSessionDetailDto();
                applySummary(detail, rs);
                detail.setTokensUsed(rs.getInt("tokens_used"));

                Map<String, CodexUiOverlayRepository.SessionOverride> overrides =
                        overlayRepository.findSessionOverrides(java.util.Collections.singletonList(sessionId));
                CodexUiOverlayRepository.SessionOverride o = overrides.get(sessionId);
                if (o != null) {
                    if (o.titleOverride != null && !o.titleOverride.trim().isEmpty()) {
                        detail.setTitle(o.titleOverride.trim());
                    }
                    detail.setFolderId(o.folderId);
                    if (o.archivedOverride != null) {
                        detail.setArchived(o.archivedOverride.intValue() == 1);
                    }
                }

                if (includeMessages) {
                    loadMessages(detail, includeNonChatRoles);
                }
                return detail;
            }
        } catch (SQLException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 Codex session 详情失败", ex);
        }
    }

    public Path getRolloutPath(String sessionId) {
        return Paths.get(getSession(sessionId, false, false).getRolloutPath());
    }

    private void loadMessages(CodexSessionDetailDto detail, boolean includeNonChatRoles) {
        Path rolloutPath = Paths.get(detail.getRolloutPath());
        if (!Files.exists(rolloutPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session 对应的 rollout 文件不存在: " + rolloutPath);
        }

        List<CodexMessageDto> messages = new ArrayList<CodexMessageDto>();
        String cliVersion = null;
        int lineNumber = 0;

        try (BufferedReader reader = Files.newBufferedReader(rolloutPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                JsonNode root = objectMapper.readTree(line);
                String type = root.path("type").asText();

                if ("session_meta".equals(type)) {
                    cliVersion = root.path("payload").path("cli_version").asText(null);
                    continue;
                }

                if (!"response_item".equals(type)) {
                    continue;
                }

                JsonNode payload = root.path("payload");
                if (!"message".equals(payload.path("type").asText())) {
                    continue;
                }

                String role = payload.path("role").asText();
                if (!includeNonChatRoles && !isChatRole(role)) {
                    continue;
                }

                String content = extractText(payload.path("content"));
                if (content.isEmpty()) {
                    continue;
                }

                String timestamp = root.path("timestamp").asText();
                String messageId = sessionMessageId(detail.getId(), lineNumber);
                messages.add(new CodexMessageDto(messageId, role, timestamp, content));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析 Codex session 文件失败", ex);
        }

        detail.setCliVersion(cliVersion);
        detail.setMessages(messages);
        detail.setMessageCount(messages.size());
    }

    private CodexSessionSummaryDto mapSummary(ResultSet rs) throws SQLException {
        CodexSessionSummaryDto summary = new CodexSessionSummaryDto();
        applySummary(summary, rs);
        return summary;
    }

    private void applySummary(CodexSessionSummaryDto target, ResultSet rs) throws SQLException {
        target.setId(rs.getString("id"));
        target.setTitle(rs.getString("title"));
        target.setCwd(rs.getString("cwd"));
        target.setSource(rs.getString("source"));
        target.setModelProvider(rs.getString("model_provider"));
        target.setCreatedAt(formatTimestamp(rs.getLong("created_at")));
        target.setUpdatedAt(formatTimestamp(rs.getLong("updated_at")));
        target.setArchived(rs.getInt("archived") == 1);
        target.setPreview(cleanPreview(rs.getString("first_user_message")));
        target.setRolloutPath(rs.getString("rollout_path"));
    }

    private Path requireStateDb() {
        Path stateDb = properties.getStateDbPath();
        if (!Files.exists(stateDb)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到 Codex 本地索引库: " + stateDb
            );
        }
        return stateDb;
    }

    private Connection openConnection(Path stateDb) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
    }

    private int normalizeLimit(Integer limit) {
        int configured = properties.getListLimit();
        if (limit == null || limit.intValue() <= 0) {
            return configured;
        }
        return Math.min(limit.intValue(), 200);
    }

    private boolean isChatRole(String role) {
        return "user".equals(role) || "assistant".equals(role);
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

    private String formatTimestamp(long rawValue) {
        if (rawValue <= 0L) {
            return null;
        }
        Instant instant = rawValue >= 1000000000000L
                ? Instant.ofEpochMilli(rawValue)
                : Instant.ofEpochSecond(rawValue);
        return instant.toString();
    }

    private String cleanPreview(String preview) {
        if (preview == null) {
            return "";
        }
        String normalized = preview.replace('\n', ' ').trim();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 140) + "...";
    }

    private String sessionMessageId(String sessionId, int lineNumber) {
        return sessionId + "-line-" + lineNumber;
    }
}
