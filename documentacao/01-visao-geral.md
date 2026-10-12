# 01. Visão geral do LinkSQL

## O problema que ele resolve

Em projetos de implantação e migração de ERP, os dados podem estar distribuídos por diferentes tecnologias e servidores. Restaurar todos os backups num único computador nem sempre é viável; também pode faltar acesso administrativo ou infraestrutura para Docker e serviços locais.

O **LinkSQL** permite cadastrar conexões JDBC e trabalhar com esses bancos diretamente, a partir de uma única interface. Ele funciona como **editor e navegador SQL**, não como um SGBD: os dados continuam nos servidores de origem.

## Principais capacidades implementadas

- Conexões MySQL, Oracle, PostgreSQL, SQL Server e Firebird, com perfis persistentes e senha protegida via Windows DPAPI.
- Explorador por conexões e schemas/bancos, agrupando tabelas, views, procedimentos e funções de acordo com o que o driver fornece.
- Editor de SQL com destaque de sintaxe, autocomplete contextual, atalhos, múltiplas abas, conexão padrão por aba e recuperação da sessão.
- Consulta **nativa** quando o SQL pode ser encaminhado com segurança a uma origem identificada; consultas **federadas** com Apache Calcite quando se combinam fontes distintas.
- Grades de resultados, contagem auxiliar, filtros locais, paginação, cópia e exportação CSV dos dados carregados.
- Histórico pesquisável, favoritos e visual claro/escuro padronizado.
- Compilação e distribuição Windows com `jpackage`, com uma versão portátil que inclui runtime Java.

## Tecnologias

Java 17, Maven, Swing, FlatLaf (temas), RSyntaxTextArea (editor), JDBC (acesso aos bancos), Apache Commons DBCP2 (pool sob demanda), Apache Calcite (federação) e Jackson (JSON de configurações). O empacotamento utiliza o `jpackage` do JDK; o instalador Windows exige WiX compatível.

## Público e cenários

Analistas SQL, responsáveis por implantações e migração de dados, equipes de suporte técnico e desenvolvimento que precisam diagnosticar ou comparar dados entre sistemas heterogêneos sem manter um banco consolidado localmente.
