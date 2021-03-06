package com.atomist.rug.cli.command.fs;

import com.atomist.rug.cli.command.CommandContext;
import com.atomist.rug.resolver.ArtifactDescriptor;
import com.atomist.source.ArtifactSource;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * {@link Thread} implementation that watches the file system backing an {@link ArtifactSource}
 * instance for modifications. In case of modifications the internal {@link CommandContext} is
 * cleared.
 */
class ArtifactSourceFileWatcherThread extends Thread {

    private ArtifactDescriptor artifact;
    private WatchService watcher;
    private Modifier[] modifiers;

    public ArtifactSourceFileWatcherThread(ArtifactDescriptor artifact) {
        this(artifact, new Modifier[0]);
    }

    public ArtifactSourceFileWatcherThread(ArtifactDescriptor artifact, Modifier... modifiers) {
        this.artifact = artifact;
        this.modifiers = modifiers;
        setDaemon(true);
        setName("FS File Watcher Thread");
        init();
        start();
    }

    private void init() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
        }
        catch (IOException e) {
            throw new RuntimeException("Error creating file watch service", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {

        final Map<WatchKey, Path> keys = new HashMap<>();

        Consumer<Path> register = register(keys);

        register.accept(Paths.get(artifact.uri()));

        while (true) {
            final WatchKey key;
            try {
                key = watcher.take(); // wait for a key to be available
            }
            catch (InterruptedException ex) {
                return;
            }

            final Path dir = keys.get(key);
            if (dir == null) {
                continue;
            }

            key.pollEvents().stream().filter(e -> (e.kind() != OVERFLOW)).forEach(e -> {
                Path p = ((WatchEvent<Path>) e).context();
                Path absPath = dir.resolve(p);

                if (absPath.toFile().isDirectory()) {
                    register.accept(absPath);
                }
                // There was a modification to the filesystem, trigger reload of ArtifactSource
                CommandContext.delete(ArtifactSource.class);
            });

            boolean valid = key.reset(); // IMPORTANT: The key must be reset after processed
            if (!valid) {
                keys.remove(key);
            }
        }
    }

    private Consumer<Path> register(final Map<WatchKey, Path> keys) {
        Consumer<Path> register = p -> {
            if (!p.toFile().exists() || !p.toFile().isDirectory()) {
                throw new RuntimeException("Folder " + p + " does not exist or is not a directory");
            }
            try {
                Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {

                        if (!keys.values().stream().anyMatch(p -> p.equals(dir))) {
                            WatchKey watchKey = dir.register(watcher, new WatchEvent.Kind<?>[] {
                                    ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY }, modifiers);
                            keys.put(watchKey, dir);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            catch (IOException e) {
                throw new RuntimeException("Error registering path " + p);
            }
        };
        return register;
    }
}
