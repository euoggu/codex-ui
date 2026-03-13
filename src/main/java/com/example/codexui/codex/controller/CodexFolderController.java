package com.example.codexui.codex.controller;

import com.example.codexui.codex.model.CodexFolderDto;
import com.example.codexui.codex.model.requests.CreateFolderRequest;
import com.example.codexui.codex.model.requests.UpdateFolderRequest;
import com.example.codexui.codex.repo.CodexUiOverlayRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/codex/folders")
public class CodexFolderController {

    private final CodexUiOverlayRepository overlayRepository;

    public CodexFolderController(CodexUiOverlayRepository overlayRepository) {
        this.overlayRepository = overlayRepository;
    }

    @GetMapping
    public List<CodexFolderDto> listFolders() {
        return overlayRepository.listFolders();
    }

    @PostMapping
    public CodexFolderDto createFolder(@RequestBody CreateFolderRequest request) {
        return overlayRepository.createFolder(request.getName());
    }

    @PatchMapping("/{folderId}")
    public void renameFolder(@PathVariable("folderId") String folderId, @RequestBody UpdateFolderRequest request) {
        overlayRepository.renameFolder(folderId, request.getName());
    }

    @PostMapping("/{folderId}/move")
    public void moveFolder(@PathVariable("folderId") String folderId,
                           @RequestParam("direction") String direction) {
        overlayRepository.moveFolder(folderId, direction);
    }

    @DeleteMapping("/{folderId}")
    public void deleteFolder(@PathVariable("folderId") String folderId) {
        overlayRepository.deleteFolder(folderId);
    }
}
