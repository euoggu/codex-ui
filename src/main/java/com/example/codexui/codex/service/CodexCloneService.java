package com.example.codexui.codex.service;

import com.example.codexui.app.config.CodexUiProperties;
import com.example.codexui.codex.config.CodexStorageProperties;
import com.example.codexui.codex.model.CodexSessionDetailDto;
import com.example.codexui.codex.repo.CodexUiOverlayRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CodexCloneService {

    private static final DateTimeFormatter ROLLOUT_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private final CodexSessionService codexSessionService;
    private final CodexStorageProperties codexStorageProperties;
    private final CodexUiProperties codexUiProperties;
    private final CodexUiOverlayRepository overlayRepository;
    private final ObjectMapper objectMapper;

    public CodexCloneService(CodexSessionService codexSessionService,
                             CodexStorageProperties codexStorageProperties,
                             CodexUiProperties codexUiProperties,
                             CodexUiOverlayRepository overlayRepository,
                             ObjectMapper objectMapper) {
        this.codexSessionService = codexSessionService;
        this.codexStorageProperties = codexStorageProperties;
        this.codexUiProperties = codexUiProperties;
        this.overlayRepository = overlayRepository;
        this.objectMapper = objectMapper;
    }

    public CodexSessionDetailDto cloneSession(String sourceSessionId) {
        CodexSessionDetailDto source = codexSessionService.getSession(sourceSessionId, false, false);
        Path sourceRollout = Paths.get(source.getRolloutPath());
        if (!Files.exists(sourceRollout)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原始 rollout 文件不存在: " + sourceRollout);
        }

        String newThreadId = UUID.randomUUID().toString();
        String cloneTitle = buildCloneTitle(source.getTitle());
        String firstUserMessage = findFirstUserMessage(sourceRollout);
        if (firstUserMessage == null || firstUserMessage.trim().isEmpty()) {
            firstUserMessage = cloneTitle;
        }

        Path targetRollout = buildTargetRolloutPath(newThreadId);
        boolean threadInserted = false;
        boolean historyWritten = false;
        boolean overlayWritten = false;

        try {
            writeClonedRollout(sourceRollout, targetRollout, newThreadId);
            insertThreadState(sourceSessionId, newThreadId, targetRollout, cloneTitle, firstUserMessage);
            threadInserted = true;

            appendHistoryLine(newThreadId, firstUserMessage);
            historyWritten = true;

            if (source.getFolderId() != null && !source.getFolderId().trim().isEmpty()) {
                overlayRepository.upsertSessionOverride(newThreadId, null, source.getFolderId(), null);
                overlayWritten = true;
            }

            return codexSessionService.getSession(newThreadId, false, false);
        } catch (RuntimeException e) {
            cleanupFailedClone(newThreadId, targetRollout, threadInserted, historyWritten, overlayWritten);
            throw e;
        }
    }

    public CodexSessionDetailDto branchFromMessage(String messageId) {
        CodexMessageId parsed = CodexMessageId.parse(messageId);
        return branchFromLine(parsed.threadId, parsed.lineNumber);
    }

    public CodexSessionDetailDto branchFromLine(String sourceSessionId, int messageLineNumber) {
        CodexSessionDetailDto source = codexSessionService.getSession(sourceSessionId, false, false);
        Path sourceRollout = Paths.get(source.getRolloutPath());
        if (!Files.exists(sourceRollout)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原始 rollout 文件不存在: " + sourceRollout);
        }

        String newThreadId = UUID.randomUUID().toString();
        String branchTitle = buildBranchTitle(source.getTitle());

        Path targetRollout = buildTargetRolloutPath(newThreadId);
        boolean threadInserted = false;
        boolean historyWritten = false;
        boolean overlayWritten = false;

        try {
            List<String> branchedLines = writeBranchedRollout(sourceRollout, targetRollout, newThreadId, messageLineNumber);
            String firstUserMessage = findFirstUserMessage(branchedLines);
            if (firstUserMessage == null || firstUserMessage.trim().isEmpty()) {
                firstUserMessage = branchTitle;
            }

            insertThreadState(sourceSessionId, newThreadId, targetRollout, branchTitle, firstUserMessage);
            threadInserted = true;

            appendHistoryLine(newThreadId, firstUserMessage);
            historyWritten = true;

            if (source.getFolderId() != null && !source.getFolderId().trim().isEmpty()) {
                overlayRepository.upsertSessionOverride(newThreadId, null, source.getFolderId(), null);
                overlayWritten = true;
            }

            return codexSessionService.getSession(newThreadId, false, false);
        } catch (RuntimeException e) {
            cleanupFailedClone(newThreadId, targetRollout, threadInserted, historyWritten, overlayWritten);
            throw e;
        }
    }

    private String buildCloneTitle(String sourceTitle) {
        String base = (sourceTitle == null || sourceTitle.trim().isEmpty()) ? "未命名 Session" : sourceTitle.trim();
        return base + " - 副本";
    }

    private String buildBranchTitle(String sourceTitle) {
        String base = (sourceTitle == null || sourceTitle.trim().isEmpty()) ? "未命名 Session" : sourceTitle.trim();
        return base + " - 分支";
    }

    private Path buildTargetRolloutPath(String newThreadId) {
        LocalDate today = LocalDate.now();
        Path dir = codexStorageProperties.getHomePath()
                .resolve("sessions")
                .resolve(String.format("%04d", today.getYear()))
                .resolve(String.format("%02d", today.getMonthValue()))
                .resolve(String.format("%02d", today.getDayOfMonth()));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建克隆 session 目录: " + dir, e);
        }
        String filename = "rollout-" + LocalDateTime.now().format(ROLLOUT_TS_FORMAT) + "-" + newThreadId + ".jsonl";
        return dir.resolve(filename);
    }

    private void writeClonedRollout(Path sourceRollout, Path targetRollout, String newThreadId) {
        try {
            List<String> lines = Files.readAllLines(sourceRollout, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源 session 文件为空，无法克隆");
            }

            JsonNode first = objectMapper.readTree(lines.get(0));
            if (!"session_meta".equals(first.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源 session 文件格式异常：首行不是 session_meta");
            }

            String nowIso = Instant.now().toString();
            ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("timestamp", nowIso);
            JsonNode payload = first.path("payload");
            if (payload.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("id", newThreadId);
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("timestamp", nowIso);
            }
            lines.set(0, objectMapper.writeValueAsString(first));
            Files.write(targetRollout, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入克隆 rollout 文件失败: " + targetRollout, e);
        }
    }

    private List<String> writeBranchedRollout(Path sourceRollout,
                                              Path targetRollout,
                                              String newThreadId,
                                              int messageLineNumber) {
        try {
            List<String> lines = Files.readAllLines(sourceRollout, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源 session 文件为空，无法分支");
            }
            if (messageLineNumber <= 0 || messageLineNumber > lines.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息行号超出范围: " + messageLineNumber);
            }

            // 校验目标行确实是 response_item.message(user/assistant)
            int index = messageLineNumber - 1;
            JsonNode root = objectMapper.readTree(lines.get(index));
            JsonNode payload = root.path("payload");
            if (!"response_item".equals(root.path("type").asText()) ||
                    !"message".equals(payload.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标行不是可分支消息");
            }
            String role = payload.path("role").asText();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持从 user/assistant 消息分支");
            }

            int paired = findPairedEventMsgIndexAround(lines, index, role);
            int endIndex = Math.max(index, paired >= 0 ? paired : index);

            List<String> out = new ArrayList<String>(endIndex + 1);
            for (int i = 0; i <= endIndex; i++) {
                out.add(lines.get(i));
            }

            // 更新 session_meta 的 id / timestamp
            JsonNode first = objectMapper.readTree(out.get(0));
            if (!"session_meta".equals(first.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源 session 文件格式异常：首行不是 session_meta");
            }
            String nowIso = Instant.now().toString();
            ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("timestamp", nowIso);
            JsonNode firstPayload = first.path("payload");
            if (firstPayload.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) firstPayload).put("id", newThreadId);
                ((com.fasterxml.jackson.databind.node.ObjectNode) firstPayload).put("timestamp", nowIso);
            }
            out.set(0, objectMapper.writeValueAsString(first));

            Files.write(targetRollout, out, StandardCharsets.UTF_8);
            return out;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入分支 rollout 文件失败: " + targetRollout, e);
        }
    }

    private int findPairedEventMsgIndexAround(List<String> lines, int responseItemIndex, String role) {
        // 已知常见情况：user -> +1 是 user_message; assistant -> -1 是 agent_message
        int[] candidates = new int[]{responseItemIndex - 1, responseItemIndex + 1};
        for (int candidate : candidates) {
            if (candidate < 0 || candidate >= lines.size()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(lines.get(candidate));
                JsonNode payload = root.path("payload");
                if (!"event_msg".equals(root.path("type").asText())) {
                    continue;
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
        }
        return -1;
    }

    private void insertThreadState(String sourceThreadId,
                                   String newThreadId,
                                   Path targetRollout,
                                   String cloneTitle,
                                   String firstUserMessage) {
        Path stateDb = codexStorageProperties.getStateDbPath();
        String selectSql = "select source, model_provider, cwd, sandbox_policy, approval_mode, tokens_used, " +
                "has_user_event, git_sha, git_branch, git_origin_url, cli_version, agent_nickname, agent_role, memory_mode " +
                "from threads where id = ?";
        String insertSql = "insert into threads (" +
                "id, rollout_path, created_at, updated_at, source, model_provider, cwd, title, sandbox_policy, approval_mode, " +
                "tokens_used, has_user_event, archived, archived_at, git_sha, git_branch, git_origin_url, cli_version, " +
                "first_user_message, agent_nickname, agent_role, memory_mode" +
                ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        long now = Instant.now().getEpochSecond();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
             PreparedStatement select = c.prepareStatement(selectSql);
             PreparedStatement insert = c.prepareStatement(insertSql)) {
            try {
                c.createStatement().execute("PRAGMA foreign_keys=ON");
            } catch (SQLException ignored) {
            }

            c.setAutoCommit(false);
            try {
                select.setString(1, sourceThreadId);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到源 session 索引: " + sourceThreadId);
                    }

                    insert.setString(1, newThreadId);
                    insert.setString(2, targetRollout.toString());
                    insert.setLong(3, now);
                    insert.setLong(4, now);
                    insert.setString(5, requireNonNull(rs.getString("source")));
                    insert.setString(6, requireNonNull(rs.getString("model_provider")));
                    insert.setString(7, requireNonNull(rs.getString("cwd")));
                    insert.setString(8, requireNonNull(cloneTitle));
                    insert.setString(9, requireNonNull(rs.getString("sandbox_policy")));
                    insert.setString(10, requireNonNull(rs.getString("approval_mode")));
                    insert.setInt(11, rs.getInt("tokens_used"));
                    insert.setInt(12, rs.getInt("has_user_event"));
                    insert.setInt(13, 0);
                    insert.setObject(14, null);
                    insert.setString(15, rs.getString("git_sha"));
                    insert.setString(16, rs.getString("git_branch"));
                    insert.setString(17, rs.getString("git_origin_url"));
                    insert.setString(18, requireNonNull(rs.getString("cli_version")));
                    insert.setString(19, requireNonNull(firstUserMessage));
                    insert.setString(20, rs.getString("agent_nickname"));
                    insert.setString(21, rs.getString("agent_role"));
                    insert.setString(22, requireNonNull(rs.getString("memory_mode")));
                    insert.executeUpdate();

                    copyDynamicTools(c, sourceThreadId, newThreadId);
                    c.commit();
                }
            } catch (RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入克隆 session 索引失败", e);
        }
    }

    private String requireNonNull(String value) {
        return value == null ? "" : value;
    }

    private void copyDynamicTools(Connection c, String sourceThreadId, String newThreadId) throws SQLException {
        if (!hasTable(c, "thread_dynamic_tools")) {
            return;
        }

        String selectSql = "select position, name, description, input_schema from thread_dynamic_tools " +
                "where thread_id = ? order by position asc";
        String insertSql = "insert into thread_dynamic_tools(thread_id, position, name, description, input_schema) " +
                "values(?,?,?,?,?)";

        try (PreparedStatement select = c.prepareStatement(selectSql);
             PreparedStatement insert = c.prepareStatement(insertSql)) {
            select.setString(1, sourceThreadId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    insert.setString(1, newThreadId);
                    insert.setInt(2, rs.getInt("position"));
                    insert.setString(3, rs.getString("name"));
                    insert.setString(4, rs.getString("description"));
                    insert.setString(5, rs.getString("input_schema"));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private boolean hasTable(Connection c, String tableName) throws SQLException {
        String sql = "select 1 from sqlite_master where type = 'table' and name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void appendHistoryLine(String newThreadId, String firstUserMessage) {
        Path historyPath = codexStorageProperties.getHomePath().resolve("history.jsonl");
        try {
            if (!Files.exists(historyPath)) {
                Files.createDirectories(historyPath.getParent());
                Files.createFile(historyPath);
            }
            Path backupDir = codexUiProperties.getHomePath().resolve("backups").resolve("history");
            Files.createDirectories(backupDir);
            String backupName = "history.jsonl.clone." +
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + ".bak";
            Files.copy(historyPath, backupDir.resolve(backupName), StandardCopyOption.REPLACE_EXISTING);

            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("session_id", newThreadId);
            node.put("ts", Instant.now().getEpochSecond());
            node.put("text", firstUserMessage);
            Files.write(historyPath, (objectMapper.writeValueAsString(node) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入 history.jsonl 失败", e);
        }
    }

    private void cleanupFailedClone(String threadId,
                                    Path rolloutPath,
                                    boolean threadInserted,
                                    boolean historyWritten,
                                    boolean overlayWritten) {
        if (overlayWritten) {
            try {
                overlayRepository.deleteSessionOverride(threadId);
            } catch (RuntimeException ignored) {
            }
        }
        if (historyWritten) {
            try {
                removeHistoryLine(threadId);
            } catch (RuntimeException ignored) {
            }
        }
        if (threadInserted) {
            try {
                deleteThreadState(threadId);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            Files.deleteIfExists(rolloutPath);
        } catch (IOException ignored) {
        }
    }

    private void removeHistoryLine(String threadId) {
        Path historyPath = codexStorageProperties.getHomePath().resolve("history.jsonl");
        if (!Files.exists(historyPath)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(historyPath, StandardCharsets.UTF_8);
            List<String> kept = new java.util.ArrayList<String>(lines.size());
            for (String line : lines) {
                if (!line.contains("\"session_id\":\"" + threadId + "\"")) {
                    kept.add(line);
                }
            }
            Files.write(historyPath, kept, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void deleteThreadState(String threadId) {
        Path stateDb = codexStorageProperties.getStateDbPath();
        String deleteTools = "delete from thread_dynamic_tools where thread_id = ?";
        String deleteThread = "delete from threads where id = ?";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + stateDb.toAbsolutePath());
             PreparedStatement ps1 = c.prepareStatement(deleteTools);
             PreparedStatement ps2 = c.prepareStatement(deleteThread)) {
            try {
                c.createStatement().execute("PRAGMA foreign_keys=ON");
            } catch (SQLException ignored) {
            }
            ps1.setString(1, threadId);
            ps1.executeUpdate();
            ps2.setString(1, threadId);
            ps2.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private String findFirstUserMessage(Path rolloutPath) {
        try {
            List<String> lines = Files.readAllLines(rolloutPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                JsonNode root = objectMapper.readTree(line);
                JsonNode payload = root.path("payload");
                if (!"response_item".equals(root.path("type").asText()) ||
                        !"message".equals(payload.path("type").asText()) ||
                        !"user".equals(payload.path("role").asText())) {
                    continue;
                }
                String text = extractText(payload.path("content"));
                if (text != null && !text.trim().isEmpty() && !text.startsWith("# AGENTS.md instructions")) {
                    return text;
                }
            }
            return null;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析源 session 首条消息失败", e);
        }
    }

    private String findFirstUserMessage(List<String> lines) {
        try {
            for (String line : lines) {
                JsonNode root = objectMapper.readTree(line);
                JsonNode payload = root.path("payload");
                if (!"response_item".equals(root.path("type").asText()) ||
                        !"message".equals(payload.path("type").asText()) ||
                        !"user".equals(payload.path("role").asText())) {
                    continue;
                }
                String text = extractText(payload.path("content"));
                if (text != null && !text.trim().isEmpty() && !text.startsWith("# AGENTS.md instructions")) {
                    return text;
                }
            }
            return null;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析分支 session 首条消息失败", e);
        }
    }

    private String extractText(JsonNode contentNode) {
        if (!contentNode.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : contentNode) {
            String text = item.path("text").asText("");
            if (!text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }
}
