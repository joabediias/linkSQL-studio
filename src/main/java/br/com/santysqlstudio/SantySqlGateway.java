package br.com.santysqlstudio;

import java.nio.file.Files;
import java.nio.file.Path;

/** Gateway 2.7 - servidor de protocolo PostgreSQL local, sem interface grafica. */
public final class SantySqlGateway {
  private SantySqlGateway() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Uso: SantySqlGateway <connections.json> [porta]");
      System.exit(2);
    }
    Path config = Path.of(args[0]).toAbsolutePath().normalize();
    int port = args.length == 2 ? Integer.parseInt(args[1]) : 55432;
    if (!Files.isRegularFile(config)) throw new IllegalArgumentException("Arquivo nao encontrado: " + config);
    String key = System.getenv("SANTYSQL_GATEWAY_PASSWORD");
    if (key == null || key.length() < 12)
      throw new IllegalArgumentException("Defina SANTYSQL_GATEWAY_PASSWORD com pelo menos 12 caracteres.");
    try (GatewaySqlExecutor executor = new GatewaySqlExecutor(config);
         GatewayPgWireServer server = new GatewayPgWireServer(port, key, executor::executeSelect)) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try { server.close(); } catch (Exception ignored) { }
      }, "santysql-gateway-shutdown"));
      System.out.println("SantySQL Gateway 2.7 - prova de conceito LOCAL");
      System.out.println("Conexoes carregadas: " + String.join(", ", executor.sourceNames()));
      System.out.println("Ouvindo em 127.0.0.1:" + server.localPort() + " (protocolo PostgreSQL / SELECT)");
      System.out.println("Encerre com Ctrl+C. Nao exponha esta porta na rede.");
      server.serve();
    }
  }
}
