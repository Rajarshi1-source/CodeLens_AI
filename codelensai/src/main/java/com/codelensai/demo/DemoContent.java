package com.codelensai.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The demo fixture on the classpath: the {@code codelens-demo/storefront} default branch
 * ({@code demo/repo/**}) and the seeded pull requests ({@code demo/prs/NN.json} + {@code NN.diff}).
 */
@Component
@Profile("demo")
public class DemoContent {

    public static final String REPO = "codelens-demo/storefront";
    public static final long GITHUB_REPO_ID = 424_242L;
    private static final String REPO_ROOT = "demo/repo/";

    public record DemoPr(int number, String title, String author, String branchFrom, String branchTo, String headSha,
                         boolean reviewOnSeed, String diff) {
    }

    private final ObjectMapper objectMapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private volatile Map<String, String> repoFiles;

    public DemoContent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, String> repoFiles() {
        Map<String, String> files = repoFiles;
        if (files == null) {
            files = new TreeMap<>();
            try {
                for (Resource r : resolver.getResources("classpath*:" + REPO_ROOT + "**/*.*")) {
                    String url = r.getURL().toString();
                    int at = url.indexOf(REPO_ROOT);
                    if (at < 0 || !r.isReadable()) {
                        continue;
                    }
                    files.put(url.substring(at + REPO_ROOT.length()), r.getContentAsString(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read demo repository", e);
            }
            repoFiles = files;
        }
        return files;
    }

    public List<DemoPr> pullRequests() {
        List<DemoPr> prs = new ArrayList<>();
        try {
            Resource[] metas = resolver.getResources("classpath*:demo/prs/*.json");
            for (Resource meta : metas) {
                JsonNode n = objectMapper.readTree(meta.getContentAsString(StandardCharsets.UTF_8));
                String file = meta.getFilename();
                String base = file == null ? "" : file.substring(0, file.length() - ".json".length());
                Resource diff = resolver.getResource("classpath:demo/prs/" + base + ".diff");
                prs.add(new DemoPr(n.path("number").asInt(), n.path("title").asString(), n.path("author").asString(),
                        n.path("branchFrom").asString(null), n.path("branchTo").asString("main"),
                        n.path("headSha").asString(), n.path("reviewOnSeed").asBoolean(true),
                        diff.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read demo pull requests", e);
        }
        prs.sort(Comparator.comparingInt(DemoPr::number));
        return prs;
    }

    /** Stable fake git blob sha for a demo path. */
    public static String blobSha(String path) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(("demo:" + path).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> asMap(Map<String, String> m) {
        return new LinkedHashMap<>(m);
    }
}
