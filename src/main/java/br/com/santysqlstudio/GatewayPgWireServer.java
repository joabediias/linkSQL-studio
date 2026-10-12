package br.com.santysqlstudio;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Prova de conceito PostgreSQL wire protocol v3 (somente Simple Query).
 * ATENCAO: este servidor NAO implementa protocolo PostgreSQL completo nem TLS.
 * O bind e obrigatoriamente 127.0.0.1. Nunca exponha via NAT/proxy.
 */
final class GatewayPgWireServer implements AutoCloseable {
  @FunctionalInterface interface SqlEngine {
    void select(String sql, GatewaySqlExecutor.ResultConsumer consumer) throws Exception;
  }
  private static final int PROTOCOL_V3 = 196608;
  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_REQUEST = 80877104;
  private static final int CANCEL_REQUEST = 80877102;
  private static final int MAX_PACKET = 16 * 1024 * 1024;
  private final ServerSocket listener;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final SqlEngine engine;
  private final String password;
  private final SecureRandom random = new SecureRandom();
  private volatile boolean closed;

  GatewayPgWireServer(int port, String password, SqlEngine engine) throws IOException {
    if (password == null || password.length() < 12)
      throw new IllegalArgumentException("Use SANTYSQL_GATEWAY_PASSWORD com pelo menos 12 caracteres.");
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("Porta invalida: " + port);
    this.listener = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
    this.password = password;
    this.engine = engine;
  }

  int localPort() { return listener.getLocalPort(); }

  void serve() throws IOException {
    while (!closed) {
      Socket socket;
      try { socket = listener.accept(); }
      catch (IOException e) { if (closed) return; throw e; }
      workers.execute(() -> {
        try (socket) {
          socket.setSoTimeout(120000);
          socket.setTcpNoDelay(true);
          handle(socket);
        } catch (Exception ex) {
          // Nao registrar SQL, credenciais nem resultados no console.
          System.err.println("Gateway: conexao encerrada (" + ex.getClass().getSimpleName() + ")");
        }
      });
    }
  }

  private void handle(Socket socket) throws Exception {
    DataInputStream in = new DataInputStream(socket.getInputStream());
    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
    String user = startup(in, out);
    if (user == null) return;
    byte[] salt = new byte[4]; random.nextBytes(salt);
    ByteArrayOutputStream challenge = new ByteArrayOutputStream();
    DataOutputStream cd = new DataOutputStream(challenge);
    cd.writeInt(5); cd.write(salt);
    send(out, 'R', challenge.toByteArray());
    out.flush();
    WireMessage passwordMessage = readMessage(in);
    if (passwordMessage == null || passwordMessage.type != 'p' ||
        !MessageDigest.isEqual(passwordHash(password, user, salt).getBytes(StandardCharsets.US_ASCII),
            readCString(passwordMessage.body).getBytes(StandardCharsets.US_ASCII))) {
      error(out, "28P01", "Usuario ou senha invalidos.");
      out.flush(); return;
    }
    sendInt(out, 'R', 0); // AuthenticationOk
    parameter(out, "server_version", "14.0");
    parameter(out, "server_encoding", "UTF8");
    parameter(out, "client_encoding", "UTF8");
    parameter(out, "DateStyle", "ISO, MDY");
    parameter(out, "integer_datetimes", "on");
    parameter(out, "standard_conforming_strings", "on");
    send(out, 'K', ByteBuffer.allocate(8).putInt((int) Thread.currentThread().getId()).putInt(random.nextInt()).array());
    ready(out);
    out.flush();
    boolean ignoreUntilSync = false;
    for (;;) {
      WireMessage msg = readMessage(in);
      if (msg == null || msg.type == 'X') return;
      if (ignoreUntilSync) {
        if (msg.type == 'S') { ignoreUntilSync = false; ready(out); out.flush(); }
        continue;
      }
      switch (msg.type) {
        case 'Q' -> {
          try { handleSql(out, readCString(msg.body)); }
          catch (Exception e) {
            String message = e instanceof SQLException sql ? sql.getMessage() : e.getMessage();
            String state = e instanceof SQLException sql && sql.getSQLState() != null
                ? sql.getSQLState() : "XX000";
            error(out, state, message == null ? "Erro ao consultar fonte" : message);
          }
          ready(out); out.flush();
        }
        case 'H' -> out.flush(); // Flush
        case 'S' -> { ready(out); out.flush(); }
        default -> {
          error(out, "0A000", "Gateway 2.7 POC suporta apenas o protocolo Simple Query. "
              + "Na URL JDBC, use preferQueryMode=simple.");
          ignoreUntilSync = true;
          out.flush();
        }
      }
    }
  }

  private String startup(DataInputStream in, DataOutputStream out) throws IOException {
    for (int attempts = 0; attempts < 4; attempts++) {
      int size;
      try { size = in.readInt(); } catch (EOFException ex) { return null; }
      if (size < 8 || size > 65536) return null;
      byte[] payload = in.readNBytes(size - 4);
      if (payload.length != size - 4) return null;
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      int protocol = buffer.getInt();
      if (protocol == SSL_REQUEST || protocol == GSS_REQUEST) {
        out.writeByte('N'); out.flush(); continue;
      }
      if (protocol == CANCEL_REQUEST) return null; // sem cancelamento nesta POC
      if (protocol != PROTOCOL_V3) { error(out, "08P01", "Protocolo PostgreSQL invalido"); out.flush(); return null; }
      String startupParams = new String(payload, 4, payload.length - 4, StandardCharsets.UTF_8);
      String[] params = startupParams.split("\\u0000");
      String user = null, database = null;
      for (int i = 0; i + 1 < params.length; i += 2) {
        if (params[i].equals("user")) user = params[i + 1];
        if (params[i].equals("database")) database = params[i + 1];
      }
      if (!"santysql".equals(user) || !"santysql".equals(database)) {
        error(out, "28000", "Use usuario e database santysql nesta POC."); out.flush(); return null;
      }
      return user;
    }
    return null;
  }

  private void handleSql(DataOutputStream out, String sql) throws Exception {
    String cleaned = sql.strip();
    if (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length()-1).stripTrailing();
    // Comandos de inicializacao do pgjdbc, sem acesso aos bancos reais.
    String upper = cleaned.toUpperCase(Locale.ROOT);
    if (upper.matches("SET\\s+(?:EXTRA_FLOAT_DIGITS|APPLICATION_NAME|CLIENT_ENCODING|DATESTYLE|STANDARD_CONFORMING_STRINGS)\\s*(?:=|TO)\\s*.+")) {
      commandComplete(out, "SET"); return;
    }
    if (upper.equals("SHOW TRANSACTION ISOLATION LEVEL")) {
      staticResult(out, "transaction_isolation", "read committed"); return;
    }
    if (upper.equals("SHOW STANDARD_CONFORMING_STRINGS")) { staticResult(out, "standard_conforming_strings", "on"); return; }
    if (upper.equals("SHOW SERVER_VERSION")) { staticResult(out, "server_version", "14.0"); return; }
    if (upper.equals("SHOW SEARCH_PATH")) { staticResult(out, "search_path", "public"); return; }
    if (upper.matches("SELECT\\s+CURRENT_DATABASE\\(\\)")) { staticResult(out, "current_database", "santysql"); return; }
    if (upper.matches("SELECT\\s+CURRENT_SCHEMA\\(\\)")) { staticResult(out, "current_schema", "public"); return; }
    if (upper.matches("SELECT\\s+(?:CURRENT_USER|SESSION_USER)")) { staticResult(out, "current_user", "santysql"); return; }
    if (upper.matches("SELECT\\s+VERSION\\(\\)")) { staticResult(out, "version", "SantySQL Gateway 2.7 POC"); return; }
    // Somente SELECT. Nao fingir que existe uma transacao ou banco PostgreSQL real.
    GatewaySqlGuard.requireReadOnlySelect(sql);
    engine.select(sql, results -> streamResults(out, results));
  }

  private void staticResult(DataOutputStream out, String name, String value) throws IOException {
    columns(out, List.of(new Column(name, 25, -1)));
    row(out, List.of(value));
    commandComplete(out, "SELECT 1");
  }

  private void streamResults(DataOutputStream out, ResultSet rs) throws Exception {
    ResultSetMetaData meta = rs.getMetaData();
    int width = meta.getColumnCount();
    if (width > 2048) throw new SQLException("Numero de colunas excede limite do Gateway");
    List<Column> cols = new ArrayList<>();
    for (int i = 1; i <= width; i++) {
      int jdbcType = meta.getColumnType(i);
      int pgType = postgresOid(jdbcType);
      cols.add(new Column(meta.getColumnLabel(i), pgType, pgTypeLength(pgType)));
    }
    columns(out, cols);
    long count = 0;
    while (rs.next()) {
      List<String> values = new ArrayList<>(width);
      for (int i = 1; i <= width; i++) {
        if (meta.getColumnType(i) == Types.BOOLEAN || meta.getColumnType(i) == Types.BIT) {
          String value = rs.getString(i);
          values.add(value == null ? null : (value.equalsIgnoreCase("true") || value.equals("1") ? "t" : "f"));
        } else if (meta.getColumnType(i) == Types.BINARY || meta.getColumnType(i) == Types.VARBINARY ||
                   meta.getColumnType(i) == Types.LONGVARBINARY || meta.getColumnType(i) == Types.BLOB) {
          byte[] binary = rs.getBytes(i);
          values.add(binary == null ? null : "\\x" + java.util.HexFormat.of().formatHex(binary));
        } else values.add(rs.getString(i));
      }
      row(out, values);
      count++;
      if (count % 256 == 0) out.flush();
    }
    commandComplete(out, "SELECT " + count);
  }

  private record Column(String label, int oid, int typeLength) {}
  private static void columns(DataOutputStream out, List<Column> columns) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(bytes);
    d.writeShort(columns.size());
    for (Column column : columns) {
      cstring(d, column.label);
      d.writeInt(0); // table OID, nao disponivel via federacao
      d.writeShort(0); // numero do atributo da tabela
      d.writeInt(column.oid);
      d.writeShort(column.typeLength);
      d.writeInt(-1); // typmod desconhecido
      d.writeShort(0); // text format
    }
    send(out, 'T', bytes.toByteArray());
  }
  private static void row(DataOutputStream out, List<String> values) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(bytes);
    d.writeShort(values.size());
    for (String value : values) {
      if (value == null) { d.writeInt(-1); continue; }
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      if (utf8.length > MAX_PACKET - 64) throw new IOException("Valor de coluna muito grande");
      d.writeInt(utf8.length); d.write(utf8);
    }
    send(out, 'D', bytes.toByteArray());
  }

  private static int postgresOid(int jdbcType) {
    return switch (jdbcType) {
      case Types.BOOLEAN, Types.BIT -> 16;
      case Types.SMALLINT, Types.TINYINT -> 21;
      case Types.INTEGER -> 23;
      case Types.BIGINT -> 20;
      case Types.FLOAT, Types.REAL -> 700;
      case Types.DOUBLE -> 701;
      case Types.NUMERIC, Types.DECIMAL -> 1700;
      case Types.DATE -> 1082;
      case Types.TIME, Types.TIME_WITH_TIMEZONE -> 1083;
      case Types.TIMESTAMP -> 1114;
      case Types.TIMESTAMP_WITH_TIMEZONE -> 1184;
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> 17;
      default -> 25; // texto generico para tipos sem representacao PostgreSQL simples
    };
  }
  private static int pgTypeLength(int oid) {
    return switch (oid) {
      case 16 -> 1;
      case 21 -> 2;
      case 23, 700 -> 4;
      case 20, 701, 1114, 1184 -> 8;
      default -> -1;
    };
  }

  private record WireMessage(char type, byte[] body) {}
  private static WireMessage readMessage(DataInputStream in) throws IOException {
    int type;
    try { type = in.readUnsignedByte(); } catch (EOFException ex) { return null; }
    int length = in.readInt();
    if (length < 4 || length > MAX_PACKET) throw new IOException("Pacote JDBC excede limite");
    byte[] payload = in.readNBytes(length - 4);
    if (payload.length != length - 4) throw new EOFException();
    return new WireMessage((char) type, payload);
  }
  private static String readCString(byte[] payload) {
    int end = 0; while (end < payload.length && payload[end] != 0) end++;
    return new String(payload, 0, end, StandardCharsets.UTF_8);
  }
  private static void cstring(OutputStream out, String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.UTF_8)); out.write(0);
  }
  private static void send(DataOutputStream out, char type, byte[] bytes) throws IOException {
    if (bytes.length > MAX_PACKET - 4) throw new IOException("Resposta muito grande");
    out.writeByte(type); out.writeInt(bytes.length + 4); out.write(bytes);
  }
  private static void sendInt(DataOutputStream out, char type, int value) throws IOException {
    send(out, type, ByteBuffer.allocate(4).putInt(value).array());
  }
  private static void parameter(DataOutputStream out, String key, String value) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(); cstring(b, key); cstring(b, value);
    send(out, 'S', b.toByteArray());
  }
  private static void commandComplete(DataOutputStream out, String command) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(); cstring(b, command); send(out, 'C', b.toByteArray());
  }
  private static void ready(DataOutputStream out) throws IOException { send(out, 'Z', new byte[]{'I'}); }
  private static void error(DataOutputStream out, String state, String message) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write('S'); cstring(b, "ERROR"); b.write('V'); cstring(b, "ERROR");
    b.write('C'); cstring(b, state);
    b.write('M'); cstring(b, message == null ? "Erro interno" : message.substring(0, Math.min(350, message.length())));
    b.write(0); send(out, 'E', b.toByteArray());
  }
  private static String passwordHash(String password, String user, byte[] salt) throws Exception {
    MessageDigest md5 = MessageDigest.getInstance("MD5");
    byte[] inner = md5.digest((password + user).getBytes(StandardCharsets.UTF_8));
    String hex = java.util.HexFormat.of().formatHex(inner);
    md5.reset(); md5.update(hex.getBytes(StandardCharsets.US_ASCII)); md5.update(salt);
    return "md5" + java.util.HexFormat.of().formatHex(md5.digest());
  }

  @Override public void close() throws IOException {
    closed = true;
    listener.close();
    workers.shutdownNow();
    try { workers.awaitTermination(3, TimeUnit.SECONDS); }
    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
  }
}
