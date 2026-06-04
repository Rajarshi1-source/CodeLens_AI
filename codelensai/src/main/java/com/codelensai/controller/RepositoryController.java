package com.codelensai.controller;

import com.codelensai.model.dto.ConnectRepoRequest;
import com.codelensai.model.dto.RepoDto;
import com.codelensai.model.entity.User;
import com.codelensai.service.RepositoryService;
import com.codelensai.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Repository connection surface for the logged-in user. */
@RestController
@RequestMapping("/api/repos")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final UserService userService;

    public RepositoryController(RepositoryService repositoryService, UserService userService) {
        this.repositoryService = repositoryService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<RepoDto>> list(@AuthenticationPrincipal OAuth2User principal) {
        return userService.currentUser(principal)
                .map(user -> ResponseEntity.ok(
                        repositoryService.listForUser(user.getId()).stream().map(RepoDto::from).toList()))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    @PostMapping("/connect")
    public ResponseEntity<RepoDto> connect(@AuthenticationPrincipal OAuth2User principal,
                                           @RequestBody ConnectRepoRequest request) {
        if (request == null || request.fullName() == null || request.fullName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = userService.currentUser(principal).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(RepoDto.from(
                repositoryService.connect(user, request.fullName(), request.githubRepoId())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal OAuth2User principal,
                                           @PathVariable long id) {
        User user = userService.currentUser(principal).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return repositoryService.disconnect(user, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
