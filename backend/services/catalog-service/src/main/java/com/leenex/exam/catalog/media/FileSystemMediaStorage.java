package com.leenex.exam.catalog.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** Stores each image as one file named by its key, written atomically. */
public class FileSystemMediaStorage implements MediaStorage {

    private static final Pattern KEY = Pattern.compile("^[a-f0-9]{32}\\.(png|jpg)$");

    private final Path root;

    public FileSystemMediaStorage(Path root) {
        try {
            this.root = Files.createDirectories(root.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new UncheckedIOException("Media directory " + root + " can't be created", e);
        }
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Path temp = Files.createTempFile(root, ".upload-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't store media " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read media " + key, e);
        }
    }

    /** Keys are checked against a strict pattern, so a key can never point outside the media directory. */
    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid media key");
        }
        return root.resolve(key);
    }
}
