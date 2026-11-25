import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class VerificarFederacaoMysql {
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    Class.forName("br.com.linksql.jdbc.LinkSqlDriver");
    Path dir = Files.createTempDirectory("linksql-e2e-");
    Path config = dir.resolve("connections.json");
    Path workspace = dir.resolve("work");
    System.setProperty("linksql.duckdb.temp", workspace.toString());
    try {
      var profiles = new ArrayList<String>();
      for (int i=1;i<=4;i++) {
        String db="origem"+i;
        try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?allowPublicKeyRetrieval=true&useSSL=false","root",System.getenv("LINKSQL_TEST_PASSWORD"));
             Statement st=c.createStatement()) {
          st.execute("CREATE DATABASE IF NOT EXISTS "+db);
          st.execute("DROP TABLE IF EXISTS "+db+".clientes");
          st.execute("CREATE TABLE "+db+".clientes (id INT, limite DECIMAL(12,2), documento VARCHAR(30), extra BLOB)");
          st.execute("INSERT INTO "+db+".clientes VALUES (1,10.25,'12.345-67',NULL),(2,20.50,'98.765-43',NULL),(3,NULL,NULL,NULL)");
        }
        profiles.add("{\"name\":\"S"+i+"\",\"type\":\"MYSQL\",\"database\":\""+db+"\",\"schema\":\""+db+"\",\"user\":\"root\",\"passwordEnv\":\"LINKSQL_TEST_PASSWORD\",\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:3306/"+db+"?allowPublicKeyRetrieval=true&useSSL=false\"}");
      }
      Files.writeString(config,"{\"connections\":["+String.join(",",profiles)+"]}",StandardCharsets.UTF_8);
      String url="jdbc:linksql:embedded?config="+java.net.URLEncoder.encode(config.toString(),StandardCharsets.UTF_8);
      String sql="SELECT id, SUM(limite) AS total FROM (SELECT id, limite FROM S1.clientes UNION ALL SELECT id, limite FROM S2.clientes UNION ALL SELECT id, limite FROM S3.clientes UNION ALL SELECT id, limite FROM S4.clientes) x GROUP BY id ORDER BY id";
      List<String> baseline=null;
      for(String mode:List.of("calcite","duckdb","auto")) {
        List<String> rows=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url+"&engine="+mode);Statement st=c.createStatement();ResultSet rs=st.executeQuery(sql)) {
          if(rs.getMetaData().getColumnCount()!=2) throw new AssertionError("Layout incorreto");
          while(rs.next()) {
            var amount=rs.getBigDecimal(2);
            rows.add(rs.getInt(1)+":"+(amount==null?"NULL":amount.stripTrailingZeros().toPlainString()));
          }
        }
        if(!rows.equals(List.of("1:41","2:82","3:NULL"))) throw new AssertionError(mode+": "+rows);
        if(baseline==null) baseline=rows;
        else if(!baseline.equals(rows)) throw new AssertionError("Divergência "+mode);
        if(Files.exists(workspace)) try(var children=Files.list(workspace)) {
          if(children.findAny().isPresent()) throw new AssertionError("Workspace não removido");
        }
      }
      System.out.println("OK: JDBC federado Calcite, DuckDB e automático, valores e limpeza");
    } finally {
      try(var paths=Files.walk(dir)) { for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
  }
}
