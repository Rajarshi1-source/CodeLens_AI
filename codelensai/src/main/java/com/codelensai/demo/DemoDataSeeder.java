package com.codelensai.demo;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.RepositoryMember;
import com.codelensai.model.entity.User;
import com.codelensai.model.enums.ReviewStatus;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryMemberRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.ReviewSessionRepository;
import com.codelensai.repository.UserRepository;
import com.codelensai.service.DiffSnapshotService;
import com.codelensai.service.ReviewService;
import com.codelensai.service.UserService;
import com.codelensai.service.index.RepoIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the {@code demo} profile: two users (alice owns, bob is a member), the {@code codelens-demo/storefront}
 * repository indexed for G1 retrieval, and five pull requests with diff snapshots. PRs 1-4 are reviewed at
 * startup by the <b>real pipeline</b> (local provider, retrieval, risk, suggestions) so the demo can never
 * drift from the product; PR 5 stays PENDING for a live re-review. Idempotent across restarts.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    public record DemoUser(long githubId, String login, String name, String avatarUrl) {
    }

    public static final Map<String, DemoUser> USERS = Map.of(
            "alice", new DemoUser(-1001L, "demo-alice", "Alice (demo)", "/demo/avatars/alice.svg"),
            "bob", new DemoUser(-1002L, "demo-bob", "Bob (demo)", "/demo/avatars/bob.svg"));

    private final UserService userService;
    private final UserRepository users;
    private final RepositoryRepository repositories;
    private final RepositoryMemberRepository members;
    private final PullRequestRepository pullRequests;
    private final ReviewSessionRepository sessions;
    private final DiffSnapshotService diffSnapshots;
    private final RepoIndexService indexService;
    private final ReviewService reviewService;
    private final DemoContent content;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    public DemoDataSeeder(UserService userService, UserRepository users, RepositoryRepository repositories,
                          RepositoryMemberRepository members, PullRequestRepository pullRequests,
                          ReviewSessionRepository sessions, DiffSnapshotService diffSnapshots,
                          RepoIndexService indexService, ReviewService reviewService, DemoContent content,
                          JdbcClient jdbc, StringRedisTemplate redis) {
        this.userService = userService;
        this.users = users;
        this.repositories = repositories;
        this.members = members;
        this.pullRequests = pullRequests;
        this.sessions = sessions;
        this.diffSnapshots = diffSnapshots;
        this.indexService = indexService;
        this.reviewService = reviewService;
        this.content = content;
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> result = seed();
        log.info("Demo data ready: {}", result);
    }

    public synchronized Map<String, Object> seed() {
        User alice = upsertUser(USERS.get("alice"));
        User bob = upsertUser(USERS.get("bob"));

        Repository repo = repositories.findByFullName(DemoContent.REPO).orElseGet(Repository::new);
        repo.setFullName(DemoContent.REPO);
        repo.setUserId(alice.getId());
        repo.setGithubRepoId(DemoContent.GITHUB_REPO_ID);
        repo.setDefaultBranch("main");
        repo.setActive(Boolean.TRUE);
        repo = repositories.save(repo);
        addMember(repo.getId(), alice.getId(), RepositoryMember.OWNER);
        addMember(repo.getId(), bob.getId(), RepositoryMember.MEMBER);

        if (!"READY".equals(repo.getIndexStatus())) {
            indexService.indexFromContents(repo.getId(), "main", content.repoFiles());
        }

        int reviewed = 0;
        for (DemoContent.DemoPr p : content.pullRequests()) {
            PullRequest pr = pullRequests.findByRepoIdAndGithubPrId(repo.getId(), 900_000L + p.number())
                    .orElseGet(PullRequest::new);
            boolean fresh = pr.getId() == null;
            int[] stats = stats(p.diff());
            pr.setRepoId(repo.getId());
            pr.setGithubPrId(900_000L + p.number());
            pr.setPrNumber(p.number());
            pr.setTitle(p.title());
            pr.setAuthor(p.author());
            pr.setBranchFrom(p.branchFrom());
            pr.setBranchTo(p.branchTo());
            pr.setHeadSha(p.headSha());
            pr.setHtmlUrl("https://github.com/" + DemoContent.REPO + "/pull/" + p.number());
            pr.setFilesChanged(stats[0]);
            pr.setAdditions(stats[1]);
            pr.setDeletions(stats[2]);
            if (fresh) {
                pr.setStatus(ReviewStatus.PENDING);
            }
            pr = pullRequests.save(pr);
            diffSnapshots.store(pr.getId(), p.headSha(), p.diff(), "demo");
            if (p.reviewOnSeed() && !sessions.existsByPrIdAndHeadSha(pr.getId(), p.headSha())) {
                reviewService.processReview(pr.getId(), p.headSha());
                reviewed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", DemoContent.REPO);
        out.put("pullRequests", content.pullRequests().size());
        out.put("reviewedNow", reviewed);
        return out;
    }

    /** Remove all demo data (cascades to PRs, sessions, comments, index) and seed again. */
    public synchronized Map<String, Object> reset() {
        repositories.findByFullName(DemoContent.REPO).ifPresent(repo -> {
            for (PullRequest pr : pullRequests.findAll()) {
                if (repo.getId().equals(pr.getRepoId())) {
                    redis.delete(Set.of("review:tokens:" + pr.getId(), "review:tokens:" + pr.getId() + ":session",
                            "review:tokens:" + pr.getId() + ":status", "presence:pr:" + pr.getId(),
                            "presence:pr:" + pr.getId() + ":seen"));
                }
            }
            jdbc.sql("DELETE FROM comment_feedback WHERE repo_id = :r").param("r", repo.getId()).update();
            jdbc.sql("DELETE FROM repositories WHERE id = :r").param("r", repo.getId()).update();
        });
        return seed();
    }

    private User upsertUser(DemoUser u) {
        User user = userService.upsertFromOAuth(u.githubId(), u.login(), u.avatarUrl(), u.login() + "@demo.codelens.local", "");
        return users.findById(user.getId()).orElse(user);
    }

    private void addMember(long repoId, long userId, String role) {
        if (!members.existsByRepoIdAndUserId(repoId, userId)) {
            members.save(new RepositoryMember(repoId, userId, role));
        }
    }

    /** {files, additions, deletions} of a unified diff. */
    static int[] stats(String diff) {
        int files = 0;
        int add = 0;
        int del = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ ")) {
                files++;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                add++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                del++;
            }
        }
        return new int[]{files, add, del};
    }
}
