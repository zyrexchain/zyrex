package org.zyrexchain.desktop;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Preserve Unicode paths across the Windows Java 11 launcher's ANSI argument conversion. */
public final class NodeBootstrap {
    private NodeBootstrap() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("The managed node bootstrap requires two encoded paths");
        }
        Path home = decodePath(args[0]);
        Path nodeJar = decodePath(args[1]);
        if (!Files.isDirectory(home) || !Files.isRegularFile(nodeJar)) {
            throw new IOException("The managed node bootstrap cannot locate its application files");
        }
        boolean systemJar = onSystemClasspath(nodeJar);
        System.setProperty("java.io.tmpdir", home.resolve("tmp").toString());
        System.setProperty("logback.configurationFile", home.resolve("logback.xml").toString());
        String mainClass;
        try (JarFile archive = new JarFile(nodeJar.toFile())) {
            Manifest manifest = archive.getManifest();
            mainClass = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
        if (mainClass == null || !mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IOException("The bundled node does not declare a valid entry point");
        }
        ClassLoader parent = systemJar ? ClassLoader.getSystemClassLoader() : ClassLoader.getPlatformClassLoader();
        URLClassLoader loader = new URLClassLoader(new URL[] {nodeJar.toUri().toURL()}, parent);
        Thread.currentThread().setContextClassLoader(loader);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { loader.close(); } catch (IOException ignored) { }
        }, "zyrex-node-classloader-shutdown"));
        try {
            Class.forName(mainClass, true, loader).getMethod("main", String[].class)
                    .invoke(null, (Object) new String[] {"--testnet", "-c", home.resolve("node.conf").toString()});
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    static Path decodePath(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 65536) {
            throw new IllegalArgumentException("The managed node path encoding is missing or too large");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new IllegalArgumentException("The managed node path encoding is not canonical");
            }
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Path path = Path.of(decoded);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("The managed node path must be absolute");
            }
            return path.normalize();
        } catch (CharacterCodingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The managed node path encoding is invalid");
        }
    }

    private static boolean onSystemClasspath(Path nodeJar) {
        for (String entry : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            try {
                if (Files.isSameFile(Path.of(entry), nodeJar)) {
                    return true;
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return false;
    }
}
