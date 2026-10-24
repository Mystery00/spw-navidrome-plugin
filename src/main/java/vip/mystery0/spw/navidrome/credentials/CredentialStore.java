package vip.mystery0.spw.navidrome.credentials;

import vip.mystery0.spw.navidrome.net.Json;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 登录载荷整体加密；同目录原子替换确保保存失败不破坏旧凭据。 */
public final class CredentialStore {
    private static final int MAX_SIZE = 1_000_000;
    private final Path directory;
    private final Path file;
    private final WindowsProtector protector;
    public CredentialStore(Path directory) { this(directory, new WindowsProtector()); }
    public CredentialStore(Path directory, WindowsProtector protector) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.file = this.directory.resolve("credentials.json");
        this.protector = Objects.requireNonNull(protector);
    }

    public synchronized void save(String address, String username, char[] password, boolean allowHttp) throws IOException {
        Objects.requireNonNull(address); Objects.requireNonNull(username); Objects.requireNonNull(password);
        if (password.length > 100_000 || address.length() > 100_000 || username.length() > 100_000) throw new IOException("Credential payload too large");
        byte[] plain;
        try (var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer)) {
            out.writeInt(1); out.writeUTF(address); out.writeUTF(username); out.writeBoolean(allowHttp);
            out.writeInt(password.length); for (char c : password) out.writeChar(c);
            plain = buffer.toByteArray();
        }
        byte[] encrypted;
        try { encrypted = protector.protect(plain); } finally { Arrays.fill(plain, (byte) 0); }
        String json = "{\"version\":1,\"address\":" + quote(address) + ",\"username\":" + quote(username)
                + ",\"allowHttp\":" + allowHttp + ",\"ciphertext\":\"" + Base64.getEncoder().encodeToString(encrypted) + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SIZE) throw new IOException("Credential file too large");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "credentials-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized Optional<SavedLogin> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        byte[] plain = null;
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_SIZE + 1);
            if (bytes.length > MAX_SIZE) throw new IOException("Credential file too large");
            Object value = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(value instanceof Map<?, ?> map) || !new java.math.BigDecimal(1).equals(map.get("version"))) throw new IOException("Unsupported credential format");
            plain = protector.unprotect(Base64.getDecoder().decode((String) map.get("ciphertext")));
            if (plain.length > MAX_SIZE) throw new IOException("Credential payload too large");
            try (var in = new DataInputStream(new ByteArrayInputStream(plain))) {
                if (in.readInt() != 1) throw new IOException("Unsupported credential payload");
                String address = in.readUTF(), username = in.readUTF(); boolean allowHttp = in.readBoolean();
                int length = in.readInt();
                if (length < 0 || length > 100_000 || length * 2 != in.available()) throw new IOException("Invalid credential payload");
                if (!address.equals(map.get("address")) || !username.equals(map.get("username")) || !Boolean.valueOf(allowHttp).equals(map.get("allowHttp"))) throw new IOException("Credential metadata mismatch");
                char[] password = new char[length]; for (int i = 0; i < length; i++) password[i] = in.readChar();
                return Optional.of(new SavedLogin(address, username, password, allowHttp));
            }
        } catch (RuntimeException e) { throw new IOException("Invalid credential file"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    public synchronized void clear() throws IOException { Files.deleteIfExists(file); }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    public static final class SavedLogin implements AutoCloseable {
        private final String address, username;
        private final char[] password;
        private final boolean allowHttp;
        private SavedLogin(String address, String username, char[] password, boolean allowHttp) {
            this.address = address; this.username = username; this.password = password; this.allowHttp = allowHttp;
        }
        public String address() { return address; }
        public String username() { return username; }
        public synchronized char[] password() { return password.clone(); }
        public boolean allowHttp() { return allowHttp; }
        @Override public synchronized void close() { Arrays.fill(password, '\0'); }
    }
}
