package com.example.codexui.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConfigurationProperties(prefix = "codex.storage")
public class CodexStorageProperties {

    private String home;
    private int listLimit = 50;

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public int getListLimit() {
        return listLimit;
    }

    public void setListLimit(int listLimit) {
        this.listLimit = listLimit;
    }

    public Path getHomePath() {
        return Paths.get(home).toAbsolutePath().normalize();
    }

    public Path getStateDbPath() {
        Path homePath = getHomePath();
        Path preferred = homePath.resolve("state_5.sqlite");
        if (preferred.toFile().exists()) {
            return preferred;
        }

        // Fallback: pick the highest state_N.sqlite present in ~/.codex.
        final Pattern p = Pattern.compile("^state_(\\d+)\\.sqlite$");
        Optional<Path> best = homePath.toFile().exists()
                ? java.util.Arrays.stream(homePath.toFile().listFiles())
                .filter(f -> f.isFile())
                .map(f -> f.toPath())
                .filter(path -> p.matcher(path.getFileName().toString()).matches())
                .max(Comparator.comparingInt(path -> {
                    Matcher m = p.matcher(path.getFileName().toString());
                    if (m.matches()) {
                        return Integer.parseInt(m.group(1));
                    }
                    return 0;
                }))
                : Optional.empty();

        return best.orElse(preferred);
    }
}
