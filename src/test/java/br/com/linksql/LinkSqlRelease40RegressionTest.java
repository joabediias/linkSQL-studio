package br.com.linksql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.prefs.*;
import javax.sql.DataSource;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.calcite.DataContext;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.*;
import org.apache.calcite.rel.type.*;
import org.apache.calcite.schema.*;
import org.apache.calcite.schema.impl.*;
import org.apache.calcite.sql.type.SqlTypeName;

/** Regressões determinísticas, sem bancos externos, credenciais ou perfil real. */
public final class LinkSqlRelease40RegressionTest {
  private static int checks;
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }
  @FunctionalInterface interface Action { void run() throws Exception; }
  private static void blocked(Action action, String message) throws Exception {
    try { action.run(); throw new AssertionError(message); }
    catch (SQLException | java.io.IOException | IllegalArgumentException expected) { checks++; }
  }
  private static Object zero(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }
  private static JdbcConnectionProfile profile(String name, String url) {
    return new JdbcConnectionProfile(name, SqlDatabaseType.POSTGRESQL, "localhost", 5432,
        "TESTE", "public", "usuario", "LINKSQL_TEST_PASSWORD", url, true);
  }

  public static final class MemoryPreferencesFactory implements PreferencesFactory {
    private static final Preferences ROOT = new MemoryPreferences(null, "");
    public Preferences userRoot() { return ROOT; }
    public Preferences systemRoot() { return ROOT; }
  }
  private static final class MemoryPreferences extends AbstractPreferences {
    private final Map<String,String> values = new HashMap<>();
    private final Map<String,MemoryPreferences> children = new HashMap<>();
    MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
    protected void putSpi(String key,String value) { values.put(key,value); }
    protected String getSpi(String key) { return values.get(key); }
    protected void removeSpi(String key) { values.remove(key); }
    protected void removeNodeSpi() { values.clear(); }
    protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
    protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
    protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name,n -> new MemoryPreferences(this,n)); }
    protected void syncSpi() {}
    protected void flushSpi() {}
  }

  private static void policyAndTransfer(Path home) throws Exception {
    var profiles = List.of(profile("SRV_PG",""), profile("SRV_OUTRO",""));
    for (String sql : List.of("INSERT INTO t VALUES (1)", "UPDATE t SET v=2", "DELETE FROM t",
        "DROP TABLE t", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT * INTO copia FROM t"))
      blocked(() -> SqlExecutionPolicy.writeRoute(sql,false,"SRV_PG",profiles), "escrita bloqueada por padrão");
    for (String sql : List.of("SELECT 'DELETE' AS texto", "/* UPDATE */ SELECT 1", "WITH x AS (SELECT 1) SELECT * FROM x"))
      check(SqlExecutionPolicy.writeRoute(sql,false,"",profiles)==null, "SELECT/CTE/literal preservados");
    for (String sql : List.of("INSERT INTO public.t VALUES (1)", "-- teste\nUPDATE t SET v='a;b'", "DELETE FROM t", "MERGE INTO t USING s ON t.id=s.id WHEN MATCHED THEN UPDATE SET v=1", "CREATE TABLE t(id INT)"))
      check(SqlExecutionPolicy.writeRoute(sql,true,"SRV_PG",profiles).source().equals("SRV_PG"), "escrita nativa autorizada");
    blocked(() -> SqlExecutionPolicy.writeRoute("DELETE FROM t",true,"",profiles),"escrita sem banco selecionado");
    blocked(() -> SqlExecutionPolicy.writeRoute("DELETE FROM SRV_PG.t",true,"SRV_PG",profiles),"prefixo federado bloqueado");
    blocked(() -> SqlExecutionPolicy.writeRoute("UPDATE t SET v=(SELECT v FROM SRV_OUTRO.t)",true,"SRV_PG",profiles),"escrita entre conexões bloqueada");
    blocked(() -> SqlExecutionPolicy.writeRoute("DELETE FROM t",true,"REMOVIDA",profiles),"origem inexistente");
    blocked(() -> SqlExecutionPolicy.writeRoute("UPDATE t SET v=1; DELETE FROM t",true,"SRV_PG",profiles),"multicomando não executado");
    blocked(() -> SqlExecutionPolicy.writeRoute("/*! DELETE FROM t */ SELECT 1",true,"SRV_PG",profiles),"comentário executável bloqueado");
    blocked(() -> SqlExecutionPolicy.writeRoute("COMMIT",true,"SRV_PG",profiles),"transação manual não suportada");
    blocked(() -> ReadOnlySqlGuard.requireReadOnlySelect("UPDATE t SET v=1"),"driver embutido permanece somente leitura");
    check(!LinkSqlSettings.allowWrites(),"escrita desativada por padrão");
    var saved = new LinkSqlSettings.Snapshot(false,2000,false,500,16,400,true);
    LinkSqlSettings.save(saved);
    check(LinkSqlSettings.snapshot().equals(saved),"novas opções persistidas");
    LinkSqlSettings.save(LinkSqlSettings.defaults());
    check(!LinkSqlSettings.allowWrites(),"restaurar padrões desativa escrita");
    var json = new ObjectMapper().readTree("{\"name\":\"SRV_PG\",\"host\":\"localhost\",\"database\":\"TESTE\",\"user\":\"usuario\",\"password\":\"segredo-proibido\",\"token\":\"segredo-proibido\"}");
    var safe = JdbcConnectionProfile.fromJson(json);
    Path file = home.resolve("export.json");
    ConnectionProfileTransfer.export(file,List.of(safe));
    String written=Files.readString(file);
    check(!written.contains("segredo-proibido") && !written.contains("\"password\"") && !written.contains("\"token\""),"segredos não exportados");
    var imported = JdbcConnectionProfile.fromJson(new ObjectMapper().readTree(written).path("connections").get(0));
    check(imported.equals(safe),"exportação pode ser importada sem perder o perfil");
    for (String url : List.of("jdbc:postgresql://host/db?password=secret", "jdbc:postgresql://u:secret@host/db", "jdbc:postgresql://host/db?accessToken=secret", "jdbc:postgresql://host/db?pass%77ord=secret"))
      blocked(() -> ConnectionProfileTransfer.profile(profile("SRV_PG",url)),"credencial em URL não exportada");
    check(Files.list(home).noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),"exportação não deixa temporário");
  }

  private static final class FakeJdbc {
    boolean auto=true, readOnly=true, closed, statementClosed, denied, failCommit;
    int commits, rollbacks, executions, maxRows=-1, fetch, timeout;
    AtomicBoolean cancellation;
    final Statement statement=(Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->switch(m.getName()) {
      case "execute" -> { executions++; if(denied)throw new SQLException("Sem permissão","42501"); if(cancellation!=null)cancellation.set(true); yield false; }
      case "getUpdateCount" -> 7;
      case "setMaxRows" -> {maxRows=(Integer)a[0];yield null;}
      case "setFetchSize" -> {fetch=(Integer)a[0];yield null;}
      case "setQueryTimeout" -> {timeout=(Integer)a[0];yield null;}
      case "close" -> {statementClosed=true;yield null;}
      default -> zero(m.getReturnType());
    });
    final Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()) {
      case "getAutoCommit" -> auto; case "isReadOnly" -> readOnly;
      case "setAutoCommit" -> {auto=(Boolean)a[0];yield null;}
      case "setReadOnly" -> {readOnly=(Boolean)a[0];yield null;}
      case "createStatement","prepareStatement" -> statement;
      case "commit" -> {if(failCommit)throw new SQLException("Conexão perdida","08006");commits++;yield null;}
      case "rollback" -> {rollbacks++;yield null;}
      case "close" -> {closed=true;yield null;}
      case "isClosed" -> closed;
      default -> zero(m.getReturnType());
    });
    DataSource source() {return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->m.getName().equals("getConnection")?connection:zero(m.getReturnType()));}
  }
  private static void transactionsAndStreaming() throws Exception {
    FakeJdbc ok=new FakeJdbc();
    var result=SqlWriteExecutor.execute(ok.connection,"UPDATE t SET x=1",100,()->false,s->{});
    check(result.affected()==7 && result.model()==null,"quantidade afetada exibida");
    check(ok.commits==1 && ok.rollbacks==0 && ok.statementClosed,"commit somente após sucesso");
    check(ok.maxRows==0 && ok.auto && ok.readOnly,"DML sem limite de linhas, estado restaurado");
    FakeJdbc denied=new FakeJdbc();denied.denied=true;
    try {SqlWriteExecutor.execute(denied.connection,"DELETE FROM t",100,()->false,s->{});throw new AssertionError("grant ignorado");}
    catch(SQLException ex) {check(ex.getSQLState().equals("42501"),"erro de permissão original preservado");}
    check(denied.commits==0 && denied.rollbacks==1,"erro provoca rollback");
    FakeJdbc cancelled=new FakeJdbc();cancelled.cancellation=new AtomicBoolean();
    try {SqlWriteExecutor.execute(cancelled.connection,"UPDATE t SET x=1",100,cancelled.cancellation::get,s->{});throw new AssertionError("cancelamento ignorado");}
    catch(CancellationException expected) {checks++;}
    check(cancelled.commits==0 && cancelled.rollbacks==1,"cancelamento antes do commit desfaz DML");
    FakeJdbc failure=new FakeJdbc();failure.failCommit=true;
    blocked(()->SqlWriteExecutor.execute(failure.connection,"UPDATE t SET x=1",100,()->false,s->{}),"erro no commit propagado");
    check(failure.rollbacks==1 && !failure.auto,"falha não confirma via restauração de autoCommit");
    for(SqlDatabaseType type:SqlDatabaseType.values()) {
      FakeJdbc jdbc=new FakeJdbc();
      try(Connection c=new FederatedStreamingDataSource(jdbc.source(),type).getConnection()) {
        c.createStatement();
        check(jdbc.fetch==(type==SqlDatabaseType.MYSQL?Integer.MIN_VALUE:1000),"leitura em blocos para "+type);
        check(jdbc.timeout==120,"timeout na origem "+type);
        if(type==SqlDatabaseType.POSTGRESQL)check(!jdbc.auto && jdbc.readOnly,"PG usa cursor em transação de leitura");
      }
      check(jdbc.closed,"conexão de origem devolvida "+type);
    }
  }

  private static final AtomicLong visits=new AtomicLong();
  private static final AtomicInteger executions=new AtomicInteger();
  private static final int ROWS=200003;
  private static final class Rows extends AbstractTable implements ScannableTable {
    final boolean fact;
    Rows(boolean fact){this.fact=fact;}
    public RelDataType getRowType(RelDataTypeFactory f) {
      var b=f.builder().add("ID",SqlTypeName.INTEGER);
      return fact?b.add("GROUP_ID",SqlTypeName.INTEGER).build():b.add("NAME",SqlTypeName.VARCHAR).build();
    }
    public Enumerable<Object[]> scan(DataContext ignored) {
      return new AbstractEnumerable<>() {public Enumerator<Object[]> enumerator(){return new Enumerator<>() {
        int row;
        public boolean moveNext(){visits.incrementAndGet();return ++row<=(fact?ROWS:100);}
        public Object[] current(){return fact?new Object[]{row,(row%100)+1}:new Object[]{row,"grupo-"+row};}
        public void reset(){row=0;}
        public void close(){}
      };}};
    }
  }
  private static Connection calcite() throws SQLException {
    Connection c=DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    SchemaPlus root=c.unwrap(CalciteConnection.class).getRootSchema();
    root.add("FACT",new AbstractSchema()).add("ITEMS",new Rows(true));
    root.add("DIM",new AbstractSchema()).add("LOOKUP",new Rows(false));
    return (Connection)Proxy.newProxyInstance(LinkSqlRelease40RegressionTest.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
      try {
        Object value=m.invoke(c,a);
        if(value instanceof Statement s && m.getName().equals("createStatement"))
          return Proxy.newProxyInstance(LinkSqlRelease40RegressionTest.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
            try {if(sm.getName().equals("executeQuery"))executions.incrementAndGet();return sm.invoke(s,sa);}
            catch(InvocationTargetException ex){throw ex.getCause();}
          });
        return value;
      }catch(InvocationTargetException ex){throw ex.getCause();}
    });
  }
  private static void cursorsAndBenchmark() throws Exception {
    String sql="SELECT f.ID, d.NAME FROM FACT.ITEMS f JOIN DIM.LOOKUP d ON f.GROUP_ID=d.ID ORDER BY f.ID";
    // Aquecimento comum, excluído da medição.
    try(Connection c=calcite();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next()){} }
    visits.set(0);executions.set(0);
    long oldStart=System.nanoTime();
    for(int page=0;page<10;page++)try(Connection c=calcite();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql+" OFFSET "+(page*1000)+" ROWS FETCH NEXT 1000 ROWS ONLY")){
      var model=SqlResultModels.readResultModel(r,1000,()->false);
      check(model.getValueAt(0,0).equals(page*1000+1),"paginação anterior: página "+page);
    }
    long oldNanos=System.nanoTime()-oldStart,oldVisits=visits.get();int oldQueries=executions.get();
    visits.set(0);executions.set(0);
    long start=System.nanoTime();
    Connection[] owned={null};Statement[] running={null};
    try(FederatedResultCursor cursor=FederatedResultCursor.open(LinkSqlRelease40RegressionTest::calcite,sql,c->owned[0]=c,s->running[0]=s,()->false)) {
      for(int page=0;page<10;page++) {
        check(cursor.canRead(page),"cursor avança sequencialmente");
        var model=cursor.read(page,1000,()->false);
        check(model.getRowCount()==1000 && model.getValueAt(0,0).equals(page*1000+1)
            && model.getValueAt(999,0).equals((page+1)*1000),"JOIN preserva linhas e ordem da página "+page);
        check(!cursor.canRead(page),"cursor não repete página já consumida");
      }
      long newNanos=System.nanoTime()-start,newVisits=visits.get();
      check(executions.get()==1 && oldQueries==10,"dez páginas exigem uma execução, contra dez anteriormente");
      check(newVisits<oldVisits,"origens são percorridas menos vezes");
      System.out.printf(Locale.ROOT,"Benchmark sintético Calcite: 200.003 linhas + dimensão de 100, JOIN ordenado, 10 páginas.\nAntes: %d execuções, %d visitas, %.3f s. Agora: %d execução, %d visitas, %.3f s.\n",oldQueries,oldVisits,oldNanos/1e9,executions.get(),newVisits,newNanos/1e9);
      for(int page=10;page<ROWS/1000;page++)check(cursor.read(page,1000,()->false).getRowCount()==1000,"páginas restantes completas");
      var tail=cursor.read(ROWS/1000,1000,()->false);
      check(tail.getRowCount()==3 && tail.getValueAt(2,0).equals(ROWS),"última página sem perda/duplicação");
      check(!cursor.canRead(ROWS/1000+1) && owned[0].isClosed(),"fim do resultado libera a conexão");
    }
    Connection[] cancelled={null};
    var cursor=FederatedResultCursor.open(LinkSqlRelease40RegressionTest::calcite,sql,c->cancelled[0]=c,s->{},()->false);
    try {cursor.read(0,1000,()->true);throw new AssertionError("cursor não cancelou");}
    catch(CancellationException expected){checks++;}
    check(cancelled[0].isClosed(),"cancelar leitura fecha os recursos");
    Connection[] failed={null};
    blocked(()->FederatedResultCursor.open(LinkSqlRelease40RegressionTest::calcite,"SELECT * FROM AUSENTE",c->failed[0]=c,s->{},()->false),"SQL inválido propagado");
    check(failed[0].isClosed(),"falha de execução fecha conexão");
    Method cache=Main.class.getDeclaredMethod("pageCache",JScrollPane.class);cache.setAccessible(true);
    JScrollPane scroll=new JScrollPane();scroll.putClientProperty("pageSize",100000);
    @SuppressWarnings("unchecked") Map<Integer,DefaultTableModel> pages=(Map<Integer,DefaultTableModel>)cache.invoke(null,scroll);
    for(int i=0;i<30;i++)pages.put(i,new DefaultTableModel());
    check(pages.size()==1,"cache limitado com páginas de 100 mil");
    scroll.putClientProperty("pageSize",1000);pages.clear();
    for(int i=0;i<30;i++)pages.put(i,new DefaultTableModel());
    check(pages.size()==8,"cache limitado a oito páginas");
  }

  private static void alwaysCountFederated() throws Exception {
    Preferences prefs = Preferences.userNodeForPackage(Main.class);
    prefs.putBoolean("countFederated", false);
    prefs.putBoolean("countFederatedV4", false);
    String values = java.util.stream.IntStream.rangeClosed(1, 200)
        .mapToObj(i -> "(" + i + ")").collect(java.util.stream.Collectors.joining(","));
    String sql = "SELECT * FROM (VALUES " + values + ") AS T(ID)";
    JScrollPane scroll = new JScrollPane(new JTable(new DefaultTableModel(new Object[]{"ID"}, 100)));
    scroll.putClientProperty("pageSize", 100);
    scroll.putClientProperty("sqlQueryKey", sql);
    Field resultsField = Main.class.getDeclaredField("resultTabs"); resultsField.setAccessible(true);
    Object previous = resultsField.get(null);
    Method count = Main.class.getDeclaredMethod("requestCount", JScrollPane.class, String.class); count.setAccessible(true);
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          JTabbedPane results = new JTabbedPane(); results.addTab("Teste", scroll);
          resultsField.set(null, results); count.invoke(null, scroll, sql);
          check(Boolean.TRUE.equals(scroll.getClientProperty("countPending")),
              "contagem federada iniciada mesmo com preferencias antigas desligadas");
        } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
      });
      AtomicReference<Object> total = new AtomicReference<>();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      do {
        SwingUtilities.invokeAndWait(() -> total.set(scroll.getClientProperty("totalRows")));
        if (total.get() != null) break;
        Thread.sleep(25);
      } while (System.nanoTime() < deadline);
      check(Long.valueOf(200).equals(total.get()), "total federado calculado automaticamente");
      LinkSqlSettings.save(LinkSqlSettings.snapshot());
      check(prefs.get("countFederated", null) == null && prefs.get("countFederatedV4", null) == null,
          "preferencias antigas de contagem removidas ao salvar");
    } finally { SwingUtilities.invokeAndWait(() -> { try { resultsField.set(null, previous); }
        catch (IllegalAccessException ex) { throw new RuntimeException(ex); } }); }
  }

  private static void commandFeedback() throws Exception {
    String[] fields = {"resultTabs", "resultSummary", "previousPageButton", "nextPageButton",
        "pageLabel", "pageSizeBox", "results"};
    Map<Field,Object> original = new LinkedHashMap<>();
    for (String name : fields) {
      Field f = Main.class.getDeclaredField(name); f.setAccessible(true); original.put(f,f.get(null));
    }
    Method show = Main.class.getDeclaredMethod("showWriteResult", SqlWriteExecutor.Result.class,
        double.class, java.awt.Component.class, String.class, int.class, String.class, String.class);
    Method trim = Main.class.getDeclaredMethod("trimResultTabs", int.class);
    Method load = Main.class.getDeclaredMethod("loadResultPage", JScrollPane.class, int.class);
    Method read = Main.class.getDeclaredMethod("showOrUpdateResultTab", DefaultTableModel.class,
        double.class, java.awt.Component.class, String.class, int.class, int.class, String.class);
    for (Method method : List.of(show,trim,load,read)) method.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> {
      try {
        JTabbedPane tabs = new JTabbedPane(); JLabel summary = new JLabel(), page = new JLabel();
        JButton previous = new JButton(), next = new JButton();
        JComboBox<String> sizes = new JComboBox<>(new String[]{"Todas (1 página)","1.000"});
        Object[] values = {tabs,summary,previous,next,page,sizes,null};
        int index = 0; for (Field field : original.keySet()) field.set(null,values[index++]);
        // Um script de escrita mantém uma confirmação por comando, inclusive zero e DDL.
        for (int slot = 0; slot < 3; slot++) {
          int affected = new int[]{7,0,-1}[slot];
          show.invoke(null,new SqlWriteExecutor.Result(null,affected),0.25,null,
              "UPDATE t SET v=1",slot,"SRV_PG","SRV_PG");
          JScrollPane scroll = (JScrollPane) tabs.getComponentAt(slot);
          DefaultTableModel model = (DefaultTableModel)((JTable)scroll.getViewport().getView()).getModel();
          check(model.getValueAt(0,0).equals("Concluído com sucesso"),"sucesso visível na grade");
          check(model.getValueAt(0,1).equals("SRV_PG"),"conexão do comando identificada");
          check(model.getValueAt(0,2).equals(affected < 0 ? "Não informado pelo banco" : String.valueOf(affected)),
              "zero preservado e contagem desconhecida não inventada");
          check(!model.isCellEditable(0,0),"confirmação não editável");
          check(!sizes.isEnabled() && !previous.isEnabled() && !next.isEnabled(),"escrita sem paginação");
          check(page.getText().contains("Comando concluído") && summary.getText().contains("COMANDO CONCLUÍDO"),
              "cabeçalho e rodapé confirmam o comando");
          check(!Boolean.TRUE.equals(scroll.getClientProperty("countPending")),"nenhum COUNT de escrita");
          load.invoke(null,scroll,1);
          check(scroll.getClientProperty("resultPage").equals(0),"escrita nunca reexecutada pela paginação");
        }
        trim.invoke(null,3);
        check(tabs.getTabCount()==3,"script somente de escrita preserva todas as confirmações");
        DefaultTableModel returned = new DefaultTableModel(new Object[][]{{123}},new Object[]{"ID"});
        show.invoke(null,new SqlWriteExecutor.Result(returned,-1),0.5,null,
            "INSERT INTO t VALUES(123) RETURNING id",0,"SRV_PG","SRV_PG");
        check(((JTable)((JScrollPane)tabs.getComponentAt(0)).getViewport().getView()).getModel()==returned,
            "dados RETURNING preservados");
        check(summary.getText().contains("1 linha(s) retornada(s)"),"retorno não confundido com registros afetados");
        read.invoke(null,returned,0.1,null,"SELECT 123",0,0,"SRV_PG");
        check(((JScrollPane)tabs.getComponentAt(0)).getClientProperty("commandFeedback")==null && sizes.isEnabled(),
            "SELECT posterior remove estado de escrita e restaura controles");
      } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
      finally {
        for (var entry : original.entrySet()) try { entry.getKey().set(null,entry.getValue()); }
          catch (IllegalAccessException ex) { throw new RuntimeException(ex); }
      }
    });
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("java.util.prefs.PreferencesFactory",MemoryPreferencesFactory.class.getName());
    Path home=Files.createTempDirectory("linksql40-regression-");
    String original=System.getProperty("user.home");System.setProperty("user.home",home.toString());
    try {
      policyAndTransfer(home);transactionsAndStreaming();cursorsAndBenchmark();alwaysCountFederated();commandFeedback();
      System.out.println("LinkSQL 4.0.0: "+checks+" verificações de escrita, transferência, cancelamento e paginação APROVADAS.");
    } finally {
      System.setProperty("user.home",original);
      try(var files=Files.walk(home)) {for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
  }
}
