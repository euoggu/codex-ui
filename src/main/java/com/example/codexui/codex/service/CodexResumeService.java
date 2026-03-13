package com.example.codexui.codex.service;

import com.example.codexui.codex.model.CodexResumeResultDto;
import com.example.codexui.codex.model.CodexSessionDetailDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodexResumeService {

    private static final String ITERM_APP_ID = "com.googlecode.iterm2";

    private final CodexSessionService codexSessionService;

    public CodexResumeService(CodexSessionService codexSessionService) {
        this.codexSessionService = codexSessionService;
    }

    public CodexResumeResultDto resumeInIterm(String sessionId) {
        CodexSessionDetailDto detail = codexSessionService.getSession(sessionId, false, false);
        String cwd = detail.getCwd();
        if (cwd == null || cwd.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该 session 没有可用的 cwd");
        }
        if (!Files.isDirectory(Paths.get(cwd))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session 对应目录不存在: " + cwd);
        }

        String command = "cd " + shellQuote(cwd) + " && codex resume " + shellQuote(sessionId);
        runAppleScript(cwd, sessionId);

        CodexResumeResultDto result = new CodexResumeResultDto();
        result.setSessionId(sessionId);
        result.setCwd(cwd);
        result.setCommand(command);
        result.setApplication("iTerm2");
        result.setLaunched(true);
        return result;
    }

    private void runAppleScript(String cwd, String sessionId) {
        List<String> command = new ArrayList<String>();
        command.add("osascript");
        command.add("-e");
        command.add("on run argv");
        command.add("-e");
        command.add("set targetDir to item 1 of argv");
        command.add("-e");
        command.add("set sessionId to item 2 of argv");
        command.add("-e");
        command.add("set cmd to \"cd \" & quoted form of targetDir & \" && codex resume \" & quoted form of sessionId");
        command.add("-e");
        command.add("tell application id \"" + ITERM_APP_ID + "\"");
        command.add("-e");
        command.add("activate");
        command.add("-e");
        command.add("set targetWindow to (create window with default profile)");
        command.add("-e");
        command.add("tell current session of current tab of targetWindow");
        command.add("-e");
        command.add("write text cmd");
        command.add("-e");
        command.add("end tell");
        command.add("-e");
        command.add("end tell");
        command.add("-e");
        command.add("end run");
        command.add(cwd);
        command.add(sessionId);

        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "调用 iTerm2 失败: " + readProcessOutput(process)
                );
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "启动 osascript 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "调用 iTerm2 被中断", e);
        }
    }

    private String readProcessOutput(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
