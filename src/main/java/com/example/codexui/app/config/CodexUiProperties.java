package com.example.codexui.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "codexui")
public class CodexUiProperties {

    private String home;
    private String db;

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    public Path getHomePath() {
        return Paths.get(home).toAbsolutePath().normalize();
    }

    public Path getDbPath() {
        return Paths.get(db).toAbsolutePath().normalize();
    }
}
