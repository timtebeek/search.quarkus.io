package io.quarkus.search.app.fetching;

import static io.quarkus.search.app.util.FileUtils.unzip;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.search.app.entity.Language;
import io.quarkus.search.app.quarkusio.QuarkusIO;
import io.quarkus.search.app.quarkusio.QuarkusIOConfig;
import io.quarkus.search.app.util.CloseableDirectory;
import io.quarkus.search.app.util.GitCloneDirectory;
import io.quarkus.search.app.util.SimpleExecutor;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;

import org.hibernate.search.util.common.impl.Closer;
import org.hibernate.search.util.common.impl.SuppressingCloser;

@ApplicationScoped
public class FetchingService {
    private static final Branches MAIN = new Branches(QuarkusIO.SOURCE_BRANCH, QuarkusIO.PAGES_BRANCH);
    private static final Branches LOCALIZED = new Branches(QuarkusIO.LOCALIZED_SOURCE_BRANCH, QuarkusIO.LOCALIZED_PAGES_BRANCH);

    @Inject
    FetchingConfig fetchingConfig;

    @Inject
    QuarkusIOConfig quarkusIOConfig;

    private final Map<URI, GitCloneDirectory.GitDirectoryDetails> repositories = new HashMap<>();
    private final Set<CloseableDirectory> closeableDirectories = new HashSet<>();

    public QuarkusIO fetchQuarkusIo() {
        CompletableFuture<GitCloneDirectory> main = null;
        Map<Language, CompletableFuture<GitCloneDirectory>> localized = new LinkedHashMap<>();
        try (SimpleExecutor executor = new SimpleExecutor(fetchingConfig.parallelism())) {
            main = executor.submit(() -> fetchQuarkusIoSite("quarkus.io", quarkusIOConfig.gitUri(), MAIN));
            for (Map.Entry<Language, QuarkusIOConfig.SiteConfig> entry : sortMap(quarkusIOConfig.localized()).entrySet()) {
                var language = entry.getKey();
                var config = entry.getValue();
                localized.put(language,
                        executor.submit(
                                () -> fetchQuarkusIoSite(language.code + ".quarkus.io", config.gitUri(), LOCALIZED)));
            }
            executor.waitForSuccessOrThrow(fetchingConfig.timeout());
            // If we get here, all tasks succeeded.
            return new QuarkusIO(quarkusIOConfig, main.join(),
                    localized.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join())));
        } catch (RuntimeException | IOException e) {
            new SuppressingCloser(e)
                    .push(main, CompletableFuture::join)
                    .pushAll(localized.values(), CompletableFuture::join);
            throw new IllegalStateException("Failed to fetch quarkus.io: " + e.getMessage(), e);
        }
    }

    private GitCloneDirectory fetchQuarkusIoSite(String siteName, URI gitUri, Branches branches) {
        URI requestedGitUri = gitUri;
        CloseableDirectory repoDir = null;
        try {
            GitCloneDirectory.GitDirectoryDetails repository = repositories.get(gitUri);
            if (repository != null) {
                return repository.pull(branches);
            }

            if (LaunchMode.DEVELOPMENT.equals(LaunchMode.current())) {
                if (isZip(gitUri)) {
                    Log.warnf("Unzipping '%s': this application is most likely indexing only a sample of %s."
                            + " See README to index the full website.",
                            gitUri, siteName);
                    repoDir = CloseableDirectory.temp(siteName);
                    unzip(Path.of(gitUri), repoDir.path());
                } else if (isFile(gitUri)) {
                    Log.infof("Using the git repository '%s' as-is without cloning to speed up indexing of %s.",
                            gitUri, siteName);
                    // In dev mode, we want to skip cloning when possible, to make things quicker.
                    repoDir = CloseableDirectory.of(Paths.get(gitUri));
                }
            }

            boolean requiresCloning;
            if (repoDir == null) {
                // We always end up here in prod and tests.
                // That's fine, because prod will always use remote (http/git) git URIs anyway,
                // never local ones (file).
                repoDir = CloseableDirectory.temp(siteName);
                requiresCloning = true;
            } else {
                // We may end up here, but only in dev mode
                requiresCloning = false;
            }

            closeableDirectories.add(repoDir);

            repository = new GitCloneDirectory.GitDirectoryDetails(repoDir.path(), branches.pages());
            repositories.put(requestedGitUri, repository);

            // If we have a local repository -- open it, and then pull the changes, clone it otherwise:
            return requiresCloning ? repository.clone(gitUri, branches) : repository.pull(branches);
        } catch (RuntimeException | IOException e) {
            new SuppressingCloser(e).push(repoDir);
            throw new IllegalStateException("Failed to fetch '%s': %s".formatted(siteName, e.getMessage()), e);
        }
    }

    private Map<Language, QuarkusIOConfig.SiteConfig> sortMap(Map<String, QuarkusIOConfig.SiteConfig> localized) {
        Map<Language, QuarkusIOConfig.SiteConfig> map = new LinkedHashMap<>();
        for (String lang : localized.keySet().stream().sorted().toList()) {
            map.put(Language.fromString(lang), localized.get(lang));
        }
        return map;
    }

    private static boolean isFile(URI uri) {
        return "file".equals(uri.getScheme());
    }

    private static boolean isZip(URI uri) {
        return isFile(uri) && uri.getPath().endsWith(".zip");
    }

    @PreDestroy
    public void cleanupTemporaryFolders() {
        try (Closer<IOException> closer = new Closer<>()) {
            closer.pushAll(CloseableDirectory::close, closeableDirectories);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to close directories '%s': %s".formatted(closeableDirectories, e.getMessage()), e);
        }
    }

    public record Branches(String sources, String pages) {
        public List<String> asRefList() {
            return List.of("refs/heads/" + sources, "refs/heads/" + pages);
        }

        public String[] asRefArray() {
            return asRefList().toArray(String[]::new);
        }
    }
}
