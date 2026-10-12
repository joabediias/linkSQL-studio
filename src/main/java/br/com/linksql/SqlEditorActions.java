package br.com.linksql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transformações de texto SQL sem consultar o banco ou recriar o documento inteiro. */
final class SqlEditorActions {
  private SqlEditorActions() {}

  private static final Pattern LINE = Pattern.compile("([^\\r\\n]*)(\\r\\n|\\n|\\r|$)");

  /** Comenta linhas não vazias; se todas já estão comentadas, remove o marcador. */
  static String toggleComments(String block) {
    if (block == null || block.isEmpty()) return block;
    Matcher check = LINE.matcher(block);
    boolean hasContent = false;
    boolean allCommented = true;
    while (check.find()) {
      String raw = check.group(1);
      if (raw.isBlank()) continue;
      hasContent = true;
      if (!raw.stripLeading().startsWith("--")) allCommented = false;
    }
    if (!hasContent) return block;
    Matcher lines = LINE.matcher(block);
    StringBuilder result = new StringBuilder(block.length() + 16);
    while (lines.find()) {
      String raw = lines.group(1);
      String ending = lines.group(2);
      if (raw.isBlank()) { result.append(raw).append(ending); continue; }
      int offset = 0;
      while (offset < raw.length() && (raw.charAt(offset) == ' ' || raw.charAt(offset) == '\t')) offset++;
      result.append(raw, 0, offset);
      if (allCommented) {
        String after = raw.substring(offset + 2);
        if (after.startsWith(" ")) after = after.substring(1);
        result.append(after);
      } else result.append("-- ").append(raw.substring(offset));
      result.append(ending);
    }
    return result.toString();
  }
}
