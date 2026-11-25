package br.com.linksql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;

/** Área de trabalho isolada por execução. Não abre conexões externas. */
final class DuckDbWorkspace implements AutoCloseable {
  private final Path directory;
  private final Connection connection;

  private DuckDbWorkspace(Path directory, Connection connection) {
    this.directory = directory;
    this.connection = connection;
  }

  static DuckDbWorkspace open(Path parent, int memoryMb, int threads) throws SQLException, IOException {
    if (memoryMb < 128 || memoryMb > 65536 || threads < 1 || threads > 64)
      throw new IllegalArgumentException("Configuração de memória ou threads inválida");
    Files.createDirectories(parent);
    Path dir = Files.createTempDirectory(parent, "linksql-duckdb-");
    Connection conn = null;
    try {
      conn = DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("workspace.duckdb"));
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("SET memory_limit = '" + memoryMb + "MB'");
        stmt.execute("SET threads = " + threads);
        stmt.execute("SET temp_directory = '" + dir.resolve("spill").toString().replace("'", "''") + "'");
        stmt.execute("SET preserve_insertion_order = false");
        stmt.execute("SET enable_external_access = false");
      }
      return new DuckDbWorkspace(dir, conn);
    } catch (SQLException | RuntimeException ex) {
      if (conn != null) try { conn.close(); } catch (SQLException close) { ex.addSuppressed(close); }
      try { deleteTree(dir); } catch (IOException close) { ex.addSuppressed(close); }
      throw ex;
    }
  }

  Connection connection() { return connection; }

  @Override public void close() throws SQLException, IOException {
    try { connection.close(); }
    finally { deleteTree(directory); }
  }

  private static void deleteTree(Path dir) throws IOException {
    try (var paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
