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
