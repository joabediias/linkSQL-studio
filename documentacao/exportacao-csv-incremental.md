# Exportacao CSV incremental — experimento de homologacao

Menu no LinkSQL: **Resultados > Exportar CSV automatizado (grandes volumes)...**

Esta funcao NAO usa a grade JTable. Para evitar o erro \`Java heap space\`, executa uma consulta por intervalo pequeno e grava os resultados diretamente em \`CSV\` (UTF-8, separado por ponto e virgula) com um cabecalho unico.

## Como usar para CALPAN
1. Abra no editor o arquivo \`CALPAN_CLIENTES_EXPORTACAO_AUTOMATICA.sql\` entregue com esta alteracao.
2. Clique em Resultados > Exportar CSV automatizado (grandes volumes).
3. Confirme \`CAD_CLI0\`, \`CLI_CODIGO\` e \`250\` codigos por lote.
4. Escolha \`CLIENTES.csv\`. Nao execute o SQL integral com Ctrl+Enter.
5. O LinkSQL descobre os prefixos numericos de seis digitos em cada servidor mencionado na consulta, executa os lotes em sequencia e escreve um unico CSV.
6. Interrompeu? Selecione o MESMO destino. O checkpoint de cada lote permite retomar sem repetir registros ja confirmados.

## Contrato de SQLs reutilizaveis
Em cada consulta de origem que contribua para os resultados, inclua no WHERE:
\`\`\`sql
/*LINKSQL_LOTE:C.CLI_CODIGO*/
\`\`\`
Em relacionamentos por codigo, como pagamentos, use:
\`\`\`sql
/*LINKSQL_LOTE:CP.PAG_CLI*/
\`\`\`
Os marcadores sao substituidos por predicados \`>= 'inicio' AND <= 'fim'\`. Isso exige que a tabela e o campo sejam SQL nativo MySQL e que os codigos sejam cadeias numericas de seis digitos com zeros a esquerda. A configuracao permite trocar tabela/campo e tamanho do lote, mas a largura de seis digitos ainda e fixa nesta versao de teste.

## Limites e seguranca
- Nao alterou o JDBC 0.5 nem as funcoes de execucao normal do SQL.
- Arquivos \`.part\` e \`.checkpoint\` sao criados ao lado do CSV e devem ficar no mesmo lugar para retomar.
- Nao substitua o SQL modelo por um SQL sem marcadores: isso provocaria nova execucao integral.
- Nao existe garantia de desempenho com redes lentas ou Calcite na primeira faixa; comece com lote de 250 e reduza a 100 se ocorrer falta de memoria (em outro destino).
- O motor **nao faz deduplicacao de chaves**: pressupoe que cada SQL retorne chaves distintas dentro de sua faixa. Compare totais e CPF/CNPJ antes da importacao.
- Clientes sem documento permanecem no CSV para auditoria, mas nao devem ser importados no ERP.
- A soma dos limites e informada ao final para conferencia; nao substitui a homologacao do CSV.
- Necessario executar em Windows para validar os sete bancos e a distribuicao portable. O teste automatizado valida o contrato de marcadores, nao dados reais.
