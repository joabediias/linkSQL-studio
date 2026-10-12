# 05 — Manual do desenvolvedor

## Ambiente recomendado

- Windows 10/11; arquitetura de desenvolvimento em **Java 17** (ou superior compatível).
- JDK completo com `java`, `javac`, `jar` e `jpackage`.
- Apache Maven instalado e no PATH (`mvn -version`).
- Uma IDE/editor, por exemplo IntelliJ IDEA ou VS Code com Java Extension Pack.
- Git para controle de versões; banco de testes com usuário de consulta (não é obrigatório para compilar).

A aplicação não exige Docker, WSL, PostgreSQL local nem serviço SQL permanente.

## Verificar ambiente

No PowerShell:

```powershell
java -version
javac -version
mvn -version
where.exe java
where.exe mvn
```

Caso `mvn` não seja reconhecido, revise `MAVEN_HOME` e `PATH`. Se a máquina possuir múltiplos JDKs, use `JAVA_HOME` do JDK correto e confira `mvn -version`.

## Abrir e compilar

```powershell
cd "E:\Projetos\linksql"
mvn clean compile
mvn exec:java
```

Ou, em uma chamada:

```powershell
mvn clean compile exec:java
```

O ponto de entrada é `br.com.linksql.Main`, configurado no `pom.xml`. O código foi reorganizado do pacote antigo para `br.com.linksql`. O Maven e os scripts de distribuição já apontam para o novo pacote.

## Organização física

```text
linksql/
  pom.xml
  README.md
  config/connections.example.json
  src/main/java/br/com/linksql/
    Main.java
    SqlConnectionRegistry.java
    SqlSessionStore.java
    SqlHistoryDialog.java
    SqlScriptText.java
    ... demais classes do inventario
  src/test/java/br/com/linksql/
    LinkSqlCoreRegressionTest.java
  testes/
    TESTAR-CORE.bat
    testar-core.ps1
  documentacao/
    00-indice.md ... 09-revisao-projeto.md
  distribuicao/
    gerar-windows.bat
    gerar-windows.ps1
  target/ (criado pelo Maven)
```

A divisão em módulos da documentação 02 é **lógica**; as classes ainda pertencem a um pacote Java comum. Novas classes que extraímos seguem o padrão de nomes existentes para não romper a compatibilidade.

## Rodar testes automatizados

```powershell
.\testes\testar-core.bat
```

O BAT utiliza Maven para compilar `src/main`/`src/test` e invoca a classe `LinkSqlCoreRegressionTest` com o classpath gerado. **Não depende de servidores nem senhas** e não é um substituto para testes de integração.

Observação: a classe é um _smoke test_ Java autocontido, **sem JUnit**. `mvn test` sozinho apenas a compila; o comando acima efetivamente a executa.

## Testes que não podem ser automatizados apenas com fontes

- Credenciais DPAPI válidas do Windows e validade das políticas de lembrança.
- Diferenças de dialeto e permissões nos cinco servidores reais.
- Resultado e custo de JOINs federados sobre tabelas volumosas.
- Produção do EXE portátil, integração com WiX e instalação em Windows.
- Compatibilidade real com o SantySQL 5.3.7 no ambiente do usuário.
