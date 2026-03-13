package com.example.codexui.codex.repo;

import com.example.codexui.app.config.CodexUiProperties;
import com.example.codexui.codex.model.CodexFolderDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CodexUiOverlayRepository {

    public static class SessionOverride {
        public final String threadId;
        public final String titleOverride;
        public final String folderId;
        public final Integer archivedOverride;

        public SessionOverride(String threadId, String titleOverride, String folderId, Integer archivedOverride) {
            this.threadId = threadId;
            this.titleOverride = titleOverride;
            this.folderId = folderId;
            this.archivedOverride = archivedOverride;
        }
    }

    public static class MessageOverride {
        public final String messageId;
        public final String threadId;
        public final int lineNumber;
        public final String contentOverride;
        public final int deleted;

        public MessageOverride(String messageId, String threadId, int lineNumber, String contentOverride, int deleted) {
            this.messageId = messageId;
            this.threadId = threadId;
            this.lineNumber = lineNumber;
            this.contentOverride = contentOverride;
            this.deleted = deleted;
        }
    }

    private final CodexUiProperties properties;

    public CodexUiOverlayRepository(CodexUiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        Path dbPath = properties.getDbPath();
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 codex-ui 数据目录: " + dbPath.getParent(), e);
        }

        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("create table if not exists folders (" +
                    "id text primary key, " +
                    "name text not null, " +
                    "created_at integer not null, " +
                    "updated_at integer not null" +
                    ")");

            s.execute("create table if not exists session_overrides (" +
                    "thread_id text primary key, " +
                    "title_override text, " +
                    "folder_id text, " +
                    "archived_override integer, " +
                    "updated_at integer not null" +
                    ")");
            s.execute("create index if not exists idx_session_overrides_folder on session_overrides(folder_id)");

            s.execute("create table if not exists message_overrides (" +
                    "message_id text primary key, " +
                    "thread_id text not null, " +
                    "line_number integer not null, " +
                    "content_override text, " +
                    "deleted integer not null default 0, " +
                    "updated_at integer not null" +
                    ")");
            s.execute("create index if not exists idx_message_overrides_thread on message_overrides(thread_id)");
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "初始化 codex-ui overlay 数据库失败: " + dbPath, e);
        }
    }

    public List<CodexFolderDto> listFolders() {
        List<CodexFolderDto> out = new ArrayList<CodexFolderDto>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("select id, name from folders order by updated_at desc")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CodexFolderDto(rs.getString("id"), rs.getString("name")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件夹失败", e);
        }
    }

    public CodexFolderDto createFolder(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹名称不能为空");
        }
        String id = "f-" + UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("insert into folders(id, name, created_at, updated_at) values(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, trimmed);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
            return new CodexFolderDto(id, trimmed);
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建文件夹失败", e);
        }
    }

    public void renameFolder(String id, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹名称不能为空");
        }
        long now = System.currentTimeMillis();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("update folders set name=?, updated_at=? where id=?")) {
            ps.setString(1, trimmed);
            ps.setLong(2, now);
            ps.setString(3, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文件夹: " + id);
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "重命名文件夹失败", e);
        }
    }

    public void deleteFolder(String id) {
        long now = System.currentTimeMillis();
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("delete from folders where id=?")) {
                ps.setString(1, id);
                int deleted = ps.executeUpdate();
                if (deleted == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到文件夹: " + id);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("update session_overrides set folder_id=null, updated_at=? where folder_id=?")) {
                ps.setLong(1, now);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除文件夹失败", e);
        }
    }

    public Map<String, SessionOverride> findSessionOverrides(List<String> threadIds) {
        Map<String, SessionOverride> out = new HashMap<String, SessionOverride>();
        if (threadIds == null || threadIds.isEmpty()) {
            return out;
        }

        StringBuilder sb = new StringBuilder("select thread_id, title_override, folder_id, archived_override from session_overrides where thread_id in (");
        for (int i = 0; i < threadIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');

        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sb.toString())) {
            for (int i = 0; i < threadIds.size(); i++) {
                ps.setString(i + 1, threadIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tid = rs.getString("thread_id");
                    out.put(tid, new SessionOverride(
                            tid,
                            rs.getString("title_override"),
                            rs.getString("folder_id"),
                            (Integer) rs.getObject("archived_override")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 session override 失败", e);
        }
    }

    public void upsertSessionOverride(String threadId, String titleOverride, String folderId, Integer archivedOverride) {
        long now = System.currentTimeMillis();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "insert into session_overrides(thread_id, title_override, folder_id, archived_override, updated_at) " +
                             "values(?,?,?,?,?) " +
                             "on conflict(thread_id) do update set " +
                             "title_override=excluded.title_override, " +
                             "folder_id=excluded.folder_id, " +
                             "archived_override=excluded.archived_override, " +
                             "updated_at=excluded.updated_at"
             )) {
            ps.setString(1, threadId);
            ps.setString(2, titleOverride);
            ps.setString(3, folderId);
            if (archivedOverride == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, archivedOverride.intValue());
            }
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存 session override 失败", e);
        }
    }

    public void deleteSessionOverride(String threadId) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("delete from session_overrides where thread_id = ?")) {
            ps.setString(1, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除 session override 失败", e);
        }
    }

    public Map<String, MessageOverride> findMessageOverridesByThread(String threadId) {
        Map<String, MessageOverride> out = new HashMap<String, MessageOverride>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "select message_id, thread_id, line_number, content_override, deleted from message_overrides where thread_id = ?"
             )) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mid = rs.getString("message_id");
                    out.put(mid, new MessageOverride(
                            mid,
                            rs.getString("thread_id"),
                            rs.getInt("line_number"),
                            rs.getString("content_override"),
                            rs.getInt("deleted")
                    ));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 message override 失败", e);
        }
    }

    public void upsertMessageOverride(String messageId, String threadId, int lineNumber, String contentOverride, int deleted) {
        long now = System.currentTimeMillis();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "insert into message_overrides(message_id, thread_id, line_number, content_override, deleted, updated_at) " +
                             "values(?,?,?,?,?,?) " +
                             "on conflict(message_id) do update set " +
                             "content_override=excluded.content_override, " +
                             "deleted=excluded.deleted, " +
                             "updated_at=excluded.updated_at"
             )) {
            ps.setString(1, messageId);
            ps.setString(2, threadId);
            ps.setInt(3, lineNumber);
            ps.setString(4, contentOverride);
            ps.setInt(5, deleted);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存 message override 失败", e);
        }
    }

    private Connection open() throws SQLException {
        Path dbPath = properties.getDbPath();
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }
}
