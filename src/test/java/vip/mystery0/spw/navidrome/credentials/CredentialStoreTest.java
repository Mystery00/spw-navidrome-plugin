package vip.mystery0.spw.navidrome.credentials;

import java.nio.file.*;
import java.io.IOException;
import java.util.Arrays;

/** 使用合成凭据验证真实 Windows 加密边界。 */
public final class CredentialStoreTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("credential-test-");
        char[] password = "合成口令'\"$`\\\n😀".toCharArray();
        try {
            CredentialStore store = new CredentialStore(dir);
            check(store.load().isEmpty(), "empty store");
            store.save("https://例子.invalid/a?x=\"", "用户\n'", password, true);
            try (var saved = new CredentialStore(dir).load().orElseThrow()) {
                check(saved.address().equals("https://例子.invalid/a?x=\""), "address round trip");
                check(saved.username().equals("用户\n'"), "username round trip");
                check(Arrays.equals(saved.password(), password), "password round trip");
                check(saved.allowHttp(), "HTTP selection round trip");
                char[] copy = saved.password(); copy[0] = 'x';
                check(Arrays.equals(saved.password(), password), "defensive password copy");
                saved.close();
                check(new String(saved.password()).chars().allMatch(c -> c == 0), "password cleared");
            }
            Path file;
            try (var files = Files.list(dir)) { file = files.findFirst().orElseThrow(); }
            byte[] previous = Files.readAllBytes(file);
            check(!Files.readString(file).contains(new String(password)), "no plaintext password");
            WindowsProtector failing = new WindowsProtector() {
                @Override public byte[] protect(byte[] input) throws IOException { throw new IOException("Synthetic failure"); }
            };
            try { new CredentialStore(dir, failing).save("new", "new", password, false); throw new AssertionError("save should fail"); }
            catch (IOException expected) { check(Arrays.equals(previous, Files.readAllBytes(file)), "old file preserved"); }
            String valid = Files.readString(file);
            Files.writeString(file, valid.replace("https://例子.invalid", "https://other.invalid"));
            failsLoad(store, password, "mismatched metadata accepted");
            Files.writeString(file, valid.replaceFirst("\"ciphertext\":\"[^\"]*\"", "\"ciphertext\":\"AAAA\""));
            failsLoad(store, password, "invalid DPAPI ciphertext accepted");
            Files.write(file, new byte[1_000_001]);
            failsLoad(store, password, "oversized file accepted");
            Files.writeString(file, "corrupt synthetic ciphertext");
            try { store.load(); throw new AssertionError("corrupt data accepted"); }
            catch (IOException expected) { check(!expected.toString().contains(new String(password)), "no secret in error"); }
            store.clear();
            check(store.load().isEmpty(), "clear store");
            System.out.println("CredentialStoreTest passed");
        } finally {
            Arrays.fill(password, '\0');
            try (var files = Files.list(dir)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
            Files.deleteIfExists(dir);
        }
    }
    private static void failsLoad(CredentialStore store, char[] password, String message) throws Exception {
        try { store.load(); throw new AssertionError(message); }
        catch (IOException expected) {
            check(!expected.toString().contains(new String(password)), "no secret in error");
            check(expected.getCause() == null, "no raw subprocess error exposed");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
