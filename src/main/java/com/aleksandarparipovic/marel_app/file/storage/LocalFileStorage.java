package com.aleksandarparipovic.marel_app.file.storage;

import com.aleksandarparipovic.marel_app.file.FileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev storage: the bytes live on local disk under {@code app.storage.local.dir},
 * one file per key (the key's slashes become directories). No credentials, no
 * network — the default when {@code app.storage.type} is unset.
 *
 * <p>Not for production behind more than one instance: two backends would not
 * see each other's files. That is exactly what {@code r2} is for.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(StorageProperties properties) {
        this.baseDir = Path.of(properties.getLocal().getDir()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Neuspešno čuvanje fajla: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Neuspešno čitanje fajla: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Neuspešno brisanje fajla: " + key, e);
        }
    }

    /**
     * Resolve a key under the base directory, refusing any key that would escape
     * it (a "../" traversal). Keys are server-generated, so this is defence in
     * depth rather than a live threat.
     */
    private Path resolve(String key) {
        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Neispravan ključ fajla: " + key);
        }
        return target;
    }
}
