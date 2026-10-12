package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Cofre local: DPAPI CurrentUser no Windows, nunca senha em texto no JSON. */
final class ConnectionVault {
  enum Duration {
    PERMANENT("Até remover ou alterar", 0),
    DAYS_7("7 dias", 7), DAYS_30("30 dias", 30),
    SESSION("Somente nesta sessão", -1);
    private final String label;
    private final int days;
    Duration(String label, int days) { this.label = label; this.days = days; }
    @Override public String toString() { return label; }
    static Duration parse(String text) {
      try { return Duration.valueOf(text); }
      catch (Exception ignored) { return PERMANENT; }
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> SESSION = new ConcurrentHashMap<>();
  private static final Path FILE = LinkSqlPaths.credentialFile();
  // -EncodedCommand (UTF-16LE) evita problemas de parsing/aspas em Windows.
  // Dados sensiveis entram apenas por stdin, nunca aparecem na linha de comando.
  private static final String DPAPI_RESULT_PREFIX = "LINKSQL_DPAPI_V1:";
  private static final String DPAPI_SCRIPT =
      "$ErrorActionPreference='Stop';" +
      "try {" +
      "Add-Type -AssemblyName System.Security -ErrorAction Stop;" +
      "$input64=[Console]::In.ReadLine();" +
      "if ([string]::IsNullOrWhiteSpace($input64)) { throw 'Entrada vazia para DPAPI' };" +
      "$raw=[Convert]::FromBase64String($input64);" +
      "$scope=[System.Security.Cryptography.DataProtectionScope]::CurrentUser;" +
      "$out=if ('__MODE__' -eq 'protect') {" +
      "[System.Security.Cryptography.ProtectedData]::Protect($raw,$null,$scope)" +
      "} else { [System.Security.Cryptography.ProtectedData]::Unprotect($raw,$null,$scope) };" +
      "[Console]::Out.WriteLine('LINKSQL_DPAPI_V1:'+[Convert]::ToBase64String($out));" +
      "exit 0" +
      "} catch { [Console]::Error.WriteLine('DPAPI: ' + $_.Exception.Message); exit 2 }";
  private ConnectionVault() {}

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
  private static String key(String name) { return name.strip().toUpperCase(Locale.ROOT); }

  static synchronized boolean has(String name) {
    if (SESSION.containsKey(key(name))) return true;
    try {
      JsonNode entry = read().path(key(name));
      return entry.isObject() && !expired(entry);
    } catch (IOException ignored) { return false; }
  }

  static synchronized Duration policy(String name) {
    if (SESSION.containsKey(key(name))) return Duration.SESSION;
    try { return Duration.parse(read().path(key(name)).path("duration").asText("PERMANENT")); }
    catch (IOException ignored) { return Duration.PERMANENT; }
  }

  static String get(String name) {
    String session = SESSION.get(key(name));
    if (session != null) return session;
    try {
      JsonNode entry = read().path(key(name));
      if (!entry.isObject()) return null;
      if (expired(entry)) { delete(name); return null; }
      String encrypted = entry.path("protected").asText("");
      if (encrypted.isBlank()) return null;
      byte[] output = dpapi(false, Base64.getDecoder().decode(encrypted));
      try { return new String(output, StandardCharsets.UTF_8); }
      finally { Arrays.fill(output, (byte)0); }
    } catch (Exception ex) {
      // Se o perfil do Windows mudou, senha deixa de ser legivel e sera solicitada novamente.
      return null;
    }
  }

  static void save(String name, char[] secret, Duration duration) throws IOException {
    if (name == null || name.isBlank() || secret == null || secret.length == 0)
      throw new IllegalArgumentException("Informe uma senha para salvar.");
    if (duration == null) duration = Duration.PERMANENT;
    String alias = key(name);
    if (duration == Duration.SESSION) {
      synchronized (ConnectionVault.class) {
        SESSION.put(alias, new String(secret));
        ObjectNode root = read();
        if (root.has(alias)) { root.remove(alias); write(root); }
      }
      return;
    }
    if (!windows()) throw new IOException("Armazenamento DPAPI exige Windows; senha nao foi persistida.");
    byte[] plain = new String(secret).getBytes(StandardCharsets.UTF_8);
    byte[] protectedBytes;
    // A criptografia pode abrir PowerShell; nao bloquear consultas ao cofre
    // nem o EDT enquanto o Windows executa a operacao externa.
    try { protectedBytes = dpapi(true, plain); }
    finally { Arrays.fill(plain, (byte)0); }
    try {
      synchronized (ConnectionVault.class) {
        ObjectNode root = read();
        ObjectNode entry = JSON.createObjectNode();
        entry.put("protected", Base64.getEncoder().encodeToString(protectedBytes));
        entry.put("duration", duration.name());
        entry.put("createdAt", System.currentTimeMillis());
        root.set(alias, entry);
        write(root);
        SESSION.remove(alias);
      }
    } finally { Arrays.fill(protectedBytes, (byte)0); }
  }

  static synchronized void delete(String name) throws IOException {
    SESSION.remove(key(name));
    ObjectNode root = read();
    if (root.has(key(name))) { root.remove(key(name)); write(root); }
  }

  static synchronized void rename(String oldName, String newName) throws IOException {
    if (key(oldName).equals(key(newName))) return;
    ObjectNode root = read();
    if (root.has(key(newName))) throw new IOException("Já existe uma credencial com o novo nome.");
    JsonNode value = root.remove(key(oldName));
    if (value != null) { root.set(key(newName), value); write(root); }
    String valueSession = SESSION.remove(key(oldName));
    if (valueSession != null) SESSION.put(key(newName), valueSession);
  }

  static synchronized void clearSession() { SESSION.clear(); }

  private static boolean expired(JsonNode entry) {
    Duration policy = Duration.parse(entry.path("duration").asText("PERMANENT"));
    if (policy.days <= 0) return false;
    long saved = entry.path("createdAt").asLong(0);
    return saved <= 0 || System.currentTimeMillis() - saved > policy.days * 86400000L;
  }

  private static ObjectNode read() throws IOException {
    if (!Files.isRegularFile(FILE)) return JSON.createObjectNode();
    JsonNode root = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    if (!(root instanceof ObjectNode object)) throw new IOException("Formato invalido do cofre LinkSQL.");
    return object;
  }

  private static void write(ObjectNode root) throws IOException {
    Files.createDirectories(FILE.getParent());
    Path temporary = Files.createTempFile(FILE.getParent(), "credentials-", ".tmp");
    try {
      JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
      try { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
      catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally { Files.deleteIfExists(temporary); }
    LinkSqlPaths.mirrorForJdbc05(FILE);
  }

  /** Lê apenas linhas identificadas: avisos do PowerShell e CLIXML não são dados DPAPI. */
  static byte[] parseDpapiResult(String stdout) throws IOException {
    String encoded = null;
    for (String line : stdout.split("\\R")) {
      String trimmed = line.strip();
      if (trimmed.startsWith(DPAPI_RESULT_PREFIX))
        encoded = trimmed.substring(DPAPI_RESULT_PREFIX.length()).strip();
    }
    if (encoded == null || encoded.isEmpty())
      throw new IOException("O PowerShell não retornou a resposta DPAPI identificada. " +
          "Verifique a política de execução e o diagnóstico do Windows.");
    try { return Base64.getDecoder().decode(encoded); }
    catch (IllegalArgumentException ex) {
      throw new IOException("Resposta DPAPI marcada, mas com Base64 inválido.", ex);
    }
  }

  private static Thread readStream(InputStream stream, ByteArrayOutputStream buffer, String label) {
    Thread reader = new Thread(() -> {
      try (InputStream in = stream) { in.transferTo(buffer); }
      catch (IOException ignored) { /* processo encerrado */ }
    }, label);
    reader.setDaemon(true);
    reader.start();
    return reader;
  }

  private static byte[] dpapi(boolean encrypt, byte[] input) throws IOException {
    if (!windows()) throw new IOException("DPAPI disponível somente no Windows.");
    String command = DPAPI_SCRIPT.replace("__MODE__", encrypt ? "protect" : "unprotect");
    String encodedCommand = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    // Nunca misturar stdout (somente resultado marcado) com stderr do PowerShell
    // (avisos e mensagens #< CLIXML). Isso evita decodificar '#' como Base64.
    Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
        "-NonInteractive", "-EncodedCommand", encodedCommand).start();
    ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
    ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
    Thread stdoutReader = readStream(process.getInputStream(), stdoutBuffer, "linksql-dpapi-stdout");
    Thread stderrReader = readStream(process.getErrorStream(), stderrBuffer, "linksql-dpapi-stderr");
    try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII)) {
      writer.write(Base64.getEncoder().encodeToString(input));
      writer.write("\n");
    }
    boolean finished;
    try { finished = process.waitFor(15, TimeUnit.SECONDS); }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt(); process.destroyForcibly();
      throw new IOException("Operação DPAPI interrompida.", ex);
    }
    if (!finished) {
      process.destroyForcibly();
      throw new IOException("A proteção DPAPI excedeu 15 segundos. Verifique o perfil e a sessão do Windows.");
    }
    try { stdoutReader.join(2000); stderrReader.join(2000); }
    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    if (process.exitValue() != 0) {
      String stderr = stderrBuffer.toString(StandardCharsets.UTF_8).strip();
      // Não imprimir conteúdo de stdout: ele pode conter material protegido.
      String details = stderr.length() > 450 ? stderr.substring(0, 450) : stderr;
      throw new IOException("Falha no Windows DPAPI (código " + process.exitValue() + "): " +
          (details.isBlank() ? "sem detalhes; consulte o diagnóstico DPAPI." : details));
    }
    return parseDpapiResult(stdoutBuffer.toString(StandardCharsets.UTF_8));
  }
}
