package io.quarkus.search.app.util;

import java.io.IOException;
import java.io.InputStream;

import io.quarkus.search.app.hibernate.InputProvider;

import io.quarkus.logging.Log;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevTree;

public class GitInputProvider implements InputProvider {
    private final Git git;
    private final RevTree tree;
    private final String path;

    public GitInputProvider(Git git, RevTree tree, String path) {
        this.git = git;
        this.tree = tree;
        this.path = path.startsWith("/") ? path.substring(1) : path;
    }

    @Override
    public InputStream open() throws IOException {
        return GitUtils.file(git.getRepository(), tree, path);
    }

    public boolean isFileAvailable() {
        try {
            return GitUtils.fileExists(git.getRepository(), tree, path);
        } catch (IOException e) {
            Log.warn("A problem occurred while trying to find a file in git tree: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String toString() {
        return "GitInputProvider{" +
                "path='" + path + '\'' +
                '}';
    }
}
