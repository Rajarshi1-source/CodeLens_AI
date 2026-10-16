package com.codelensai.support;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.Repository;
import com.codelensai.model.entity.RepositoryMember;
import com.codelensai.model.entity.User;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryMemberRepository;
import com.codelensai.repository.RepositoryRepository;
import com.codelensai.repository.UserRepository;
import com.codelensai.service.DiffSnapshotService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Builds users / repositories / PRs for integration tests. Everything is uniquely named so tests can share
 * a long-lived database (the external-infra mode) without colliding.
 */
@Component
public class TestFixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private final UserRepository users;
    private final RepositoryRepository repositories;
    private final RepositoryMemberRepository members;
    private final PullRequestRepository pullRequests;
    private final DiffSnapshotService diffSnapshots;

    public TestFixtures(UserRepository users, RepositoryRepository repositories, RepositoryMemberRepository members,
                        PullRequestRepository pullRequests, DiffSnapshotService diffSnapshots) {
        this.users = users;
        this.repositories = repositories;
        this.members = members;
        this.pullRequests = pullRequests;
        this.diffSnapshots = diffSnapshots;
    }

    public static long next() {
        return SEQ.incrementAndGet();
    }

    public User user(String prefix) {
        long n = next();
        User u = new User();
        u.setGithubId(-(10_000_000L + n));
        u.setUsername(prefix + "-" + n);
        u.setAvatarUrl("https://avatars.example/" + n);
        u.setAccessToken("");
        return users.saveAndFlush(u);
    }

    public Repository repo(User owner) {
        Repository r = new Repository();
        r.setFullName("it-org/repo-" + next());
        r.setGithubRepoId(next());
        r.setUserId(owner.getId());
        r = repositories.saveAndFlush(r);
        members.saveAndFlush(new RepositoryMember(r.getId(), owner.getId(), RepositoryMember.OWNER));
        return r;
    }

    public void addMember(Repository repo, User user) {
        members.saveAndFlush(new RepositoryMember(repo.getId(), user.getId(), RepositoryMember.MEMBER));
    }

    public PullRequest pr(Repository repo, String diff) {
        PullRequest pr = new PullRequest();
        pr.setRepoId(repo.getId());
        pr.setGithubPrId(next());
        pr.setPrNumber((int) (next() % 100_000));
        pr.setTitle("it: change " + next());
        pr.setAuthor("octocat");
        pr.setHeadSha(Long.toHexString(next()) + "deadbeef");
        pr = pullRequests.saveAndFlush(pr);
        if (diff != null) {
            diffSnapshots.store(pr.getId(), pr.getHeadSha(), diff, "test");
        }
        return pr;
    }

    /** A MockMvc request authenticated exactly like a GitHub OAuth2 login for {@code user}. */
    public static RequestPostProcessor as(User user) {
        Map<String, Object> attrs = Map.of("id", user.getGithubId(), "login", user.getUsername(),
                "avatar_url", String.valueOf(user.getAvatarUrl()));
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "login");
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github"));
    }
}
