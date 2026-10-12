# Documentação oficial — LinkSQL 3.3.1

**Objetivo:** explicar a aplicação de maneira que seja possível utilizá-la, apresentá-la e dar manutenção no código sem depender de conhecimento tácito de versões anteriores.

| Ordem | Documento                                                      | O que responde                                                                      |
| ----- | -------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| 01    | [Visão geral](01-visao-geral.md)                               | O que é o LinkSQL, que problema resolve e quais são seus componentes?               |
| 02    | [Arquitetura](./02-arquitetura.md)                             | Como UI, JDBC, roteamento nativo, Calcite e persistência se relacionam?             |
| 03    | [Inventário completo do código-fonte](03-guia-codigo-fonte.md) | Para que serve **cada classe Java**? Onde mexer para evoluir cada funcionalidade?   |
| 04    | [Fluxos de execução](04-fluxos-e-motor-sql.md)                 | O que acontece quando clico em Executar? Como passam paginação, DPAPI, JOINs e CSV? |
| 05    | [Manual do desenvolvedor](05-manual-desenvolvedor.md)          | Como preparar o ambiente, depurar, testar e modificar código com segurança?         |
| 06    | [Manual do usuário](06-manual-usuario.md)                      | Como cadastrar conexão, navegar, consultar, paginar, salvar e exportar?             |
| 07    | [Distribuição Windows](07-distribuicao-windows.md)             | Como gerar o LinkSQL.exe portátil e o instalador?                                   |
| 08    | [Segurança e armazenamento](08-seguranca-e-dados.md)           | Onde ficam dados, credenciais, permissões e seus limites de proteção?               |
| 09    | [Revisão do projeto e migração](09-revisao-projeto.md) | Quais arquivos saíram, o que foi renomeado e como migrar sem perder dados? |

## Como estudar

1. Leia os documentos **01 e 02** para compreender os conceitos.
2. Use o **03** enquanto navega pelos arquivos Java no VS Code ou IntelliJ.
3. Acompanhe os exemplos do **04** com o LinkSQL aberto e uma conexão de testes.
4. Estude o **05** para aprender a compilar, testar e editar com segurança.
5. Consulte os documentos **07 e 08** antes de alterar autenticação, execução ou distribuição.
