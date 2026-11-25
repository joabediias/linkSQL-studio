import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ServiceLoader;

/** Teste simples que nao precisa de credenciais reais nem de servidores de banco. */
public class VerificarLinkSqlJdbc {
    public static void main(String[] args) throws Exception {
        Class.forName("br.com.linksql.ConnectionVault");
        boolean encontrou = false;
        for (Driver driver : ServiceLoader.load(Driver.class)) {
            if (driver.getClass().getName().equals("br.com.linksql.jdbc.LinkSqlDriver")) {
                encontrou = true;
                if (!driver.acceptsURL("jdbc:linksql:embedded"))
                    throw new AssertionError("Driver nao aceita URL LinkSQL");
            }
        }
        if (!encontrou) throw new AssertionError("LinkSQL nao encontrado pelo ServiceLoader JDBC");
        Path temp = Files.createTempFile("linksql-jdbc-teste-", ".json");
        try {
            Files.writeString(temp, "{\"connections\":[]}", StandardCharsets.UTF_8);
            String config = java.net.URLEncoder.encode(temp.toString(), StandardCharsets.UTF_8);
            try (Connection con = DriverManager.getConnection("jdbc:linksql:embedded?config=" + config);
                 Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 AS CODIGO, 'OK' AS DESCRICAO")) {
                if (!rs.next() || rs.getInt(1) != 1 || !"OK".equals(rs.getString(2)))
                    throw new AssertionError("SELECT de teste retornou valores incorretos");
                if (rs.next()) throw new AssertionError("SELECT de teste retornou linhas extras");
            }
            // Valida que a biblioteca nativa DuckDB foi incluída no bundle sombreado.
            try (Connection disk = DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = disk.createStatement();
                 ResultSet rs = st.executeQuery("SELECT i AS CODIGO, CAST(i AS DECIMAL(12,2)) AS VALOR FROM range(10000) t(i) ORDER BY i")) {
                int count = 0;
                while (rs.next()) {
                    if (rs.getInt("CODIGO") != count ||
                        rs.getBigDecimal("VALOR").compareTo(java.math.BigDecimal.valueOf(count)) != 0)
                        throw new AssertionError("ResultSet DuckDB incorreto");
                    count++;
                }
                if (count != 10000) throw new AssertionError("ResultSet DuckDB incompleto");
            }
            for (String mode : new String[]{"calcite", "auto", "duckdb"}) {
                try (Connection con = DriverManager.getConnection("jdbc:linksql:embedded?config=" + config + "&engine=" + mode);
                     Statement st = con.createStatement()) {
                    if (!con.isReadOnly()) throw new AssertionError("Driver deve ser somente leitura");
                    try {
                        st.executeQuery("DELETE FROM T");
                        throw new AssertionError("Comando de escrita aceito");
                    } catch (java.sql.SQLException expected) { }
                }
            }
            try {
                DriverManager.getConnection("jdbc:linksql:embedded?config=" + config + "&engine=invalido").close();
                throw new AssertionError("Motor inválido aceito");
            } catch (java.sql.SQLException expected) { }
            System.out.println("TESTE OK: driver registrado, conexao embutida e SELECT retornando resultado.");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
