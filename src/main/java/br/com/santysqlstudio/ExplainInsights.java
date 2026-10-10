package br.com.santysqlstudio;

import javax.swing.table.TableModel;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Interpretacao conservadora de EXPLAIN. Nao executa a consulta nem promete ganhos de indices. */
final class ExplainInsights {
  private ExplainInsights() {}

  static List<String> summarize(TableModel plan, boolean mysql) {
    List<String> messages = new ArrayList<>();
    if (!mysql) {
      messages.add("Plano logico do Calcite: mostra as operacoes planejadas, nao o tempo real de execucao.");
      messages.add("Indices fisicos e linhas realmente examinadas devem ser verificados com EXPLAIN no MySQL de cada servidor.");
      return messages;
    }
    if (plan == null || plan.getRowCount() == 0) {
      messages.add("O MySQL nao retornou linhas para analisar neste plano.");
      return messages;
    }
    int type = column(plan, "type");
    int table = column(plan, "table");
    int index = column(plan, "key");
    int rows = column(plan, "rows");
    int extra = column(plan, "Extra");
    int possible = column(plan, "possible_keys");
    if (type < 0) {
      messages.add("Formato de EXPLAIN desconhecido. Consulte os detalhes tecnicos abaixo.");
      return messages;
    }
    NumberFormat nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR"));
    for (int r = 0; r < plan.getRowCount() && messages.size() < 7; r++) {
      String name = val(plan, r, table);
      String prefix = name.isBlank() ? "Etapa " + (r + 1) : "Tabela " + name;
      String access = val(plan, r, type).toLowerCase(Locale.ROOT);
      String chosen = val(plan, r, index);
      String candidates = val(plan, r, possible);
      String estimated = val(plan, r, rows);
      String suffix = "";
      if (!estimated.isBlank()) {
        try { suffix = " (estimativa: " + nf.format(new BigDecimal(estimated).longValue()) + " linha(s) examinada(s))"; }
        catch (NumberFormatException ignored) { suffix = " (linhas estimadas: " + estimated + ")"; }
      }
      switch (access) {
        case "const", "system" -> messages.add(prefix + ": busca pontual, com acesso muito seletivo" + suffix + ".");
        case "eq_ref" -> messages.add(prefix + ": busca por indice com no maximo uma correspondencia por combinacao de chaves" + suffix + ".");
        case "ref", "range", "ref_or_null", "index_merge" -> messages.add(prefix + ": acesso indexado (" + access + ")"
            + (chosen.isBlank() ? "" : " usando " + chosen) + suffix + ".");
        case "all" -> messages.add(prefix + ": varredura de tabela completa (ALL)" + suffix
            + ". Pode ser adequada em tabelas pequenas; em grandes tabelas, avalie filtros e indices.");
        case "index" -> messages.add(prefix + ": leitura do indice (index), possivelmente abrangendo muitas entradas" + suffix + ".");
        default -> {
          if (!access.isBlank()) messages.add(prefix + ": acesso " + access + suffix + ".");
        }
      }
      if (chosen.isBlank() && !candidates.isBlank()) {
        messages.add(prefix + ": havia indices candidatos (" + candidates
            + "), mas nenhum foi selecionado; isso nao indica, por si so, um problema.");
      }
      String note = val(plan, r, extra).toLowerCase(Locale.ROOT);
      if (note.contains("using filesort")) {
        messages.add(prefix + ": ordenacao adicional (Using filesort). Nao significa necessariamente uso do disco.");
      }
      if (note.contains("using temporary")) {
        messages.add(prefix + ": usa tabela temporaria; avalie o custo se a consulta for lenta.");
      }
    }
    if (messages.isEmpty()) messages.add("Veja os detalhes tecnicos. O plano representa estimativas do otimizador.");
    messages.add("EXPLAIN nao executa o SELECT: para medir tempo real, execute a consulta separadamente.");
    return messages;
  }

  /** Panorama quantitativo do plano, sem atribuir um custo real a partir de estimativas. */
  static String headline(TableModel plan, boolean mysql) {
    if (!mysql) return "Plano federado: operações lógicas, sem métricas de execução real";
    if (plan == null || plan.getRowCount() == 0) return "Nenhuma etapa retornada pelo EXPLAIN";
    int type = column(plan, "type");
    int rows = column(plan, "rows");
    int scans = 0, indexed = 0;
    long estimated = 0;
    boolean known = false;
    for (int i = 0; i < plan.getRowCount(); i++) {
      String access = val(plan, i, type).toLowerCase(Locale.ROOT);
      if (access.equals("all")) scans++;
      if (List.of("index", "ref", "range", "const", "system", "eq_ref", "index_merge", "ref_or_null")
          .contains(access)) indexed++;
      String count = val(plan, i, rows);
      if (!count.isBlank()) {
        try { estimated = Math.addExact(estimated, new BigDecimal(count).longValueExact()); known = true; }
        catch (ArithmeticException | NumberFormatException ignored) { known = false; }
      }
    }
    NumberFormat nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR"));
    return nf.format(plan.getRowCount()) + " etapa(s) · " + scans + " varredura(s) completa(s) · "
        + indexed + " acesso(s) indexado(s)" + (known ? " · ~" + nf.format(estimated) + " linhas estimadas*" : "")
        + "  (*estimativas não aditivas em JOINs)";
  }

  static String helpText() {
    return "COMO INTERPRETAR O EXPLAIN\n\n"
        + "table: tabela ou etapa analisada.\n\n"
        + "type: método de acesso. ALL indica varredura completa; index, leitura de índice; "
        + "ref, range, eq_ref e const indicam diferentes formas de acesso seletivo. "
        + "Nenhum tipo garante desempenho por si só.\n\n"
        + "possible_keys: índices que o otimizador poderia considerar.\n\n"
        + "key: índice efetivamente escolhido; vazio quando não foi escolhido.\n\n"
        + "rows: quantidade ESTIMADA de linhas examinadas por etapa, não o número de registros retornados. "
        + "Em JOINs, somar rows de etapas diferentes não representa o custo total.\n\n"
        + "filtered: percentual ESTIMADO de linhas que passam pelo filtro (quando disponível).\n\n"
        + "Extra: operações auxiliares. Using filesort indica ordenação adicional, não necessariamente uso de disco. "
        + "Using temporary indica o uso de uma tabela temporária.\n\n"
        + "DICA: teste consultas semelhantes com e sem filtros e compare o plano. "
        + "EXPLAIN mostra estimativas; o tempo real exige executar a consulta. "
        + "Não crie índices sem avaliar cardinalidade, volume e o impacto nas escritas.";
  }

  private static int column(TableModel model, String name) {
    for (int i = 0; i < model.getColumnCount(); i++) {
      if (model.getColumnName(i).equalsIgnoreCase(name)) return i;
    }
    return -1;
  }

  private static String val(TableModel plan, int row, int col) {
    if (col < 0) return "";
    Object item = plan.getValueAt(row, col);
    if (item == null || item.toString().equalsIgnoreCase("null")) return "";
    return item.toString().trim();
  }
}
