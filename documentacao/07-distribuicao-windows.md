# 07. Compilação e distribuição Windows

## Objetivo

Produzir um executável **LinkSQL.exe** portátil com Java incluído e, quando o ambiente possuir WiX compatível, um instalador `.exe`. A distribuição **não deve conter** a pasta de credenciais, os arquivos SQL particulares ou os JSON de conexão reais do usuário.

## Pré-requisitos

- Windows 10/11, JDK 17+ completo (`jar.exe`, `java.exe`, `jpackage.exe`).
- Maven funcional (`mvn -version`).
- WiX Toolset compatível com a versão do `jpackage` **somente para o instalador**.
- Recursos locais e espaço livre para o runtime e os JARs JDBC.

## Build recomendado

```powershell
cd "E:\Projetos\linksql"
mvn clean compile
.\distribuicao\gerar-windows.bat
```

O BAT chama `distribuicao/gerar-windows.ps1`. O script:

1. Identifica Maven e um JDK com jpackage, incluindo busca em JAVA_HOME, PATH e locais comuns.
2. Executa `mvn -B -DskipTests clean package` e copia dependências runtime.
3. Prepara um JAR **fino** `LinkSQL.jar` e seu `Class-Path` no manifesto, deixando as bibliotecas JDBC/Calcite separadas em `lib/`.
4. Usa `jpackage --type app-image` com o nome e versão LinkSQL **3.3.1**, Java incluído e configuração de módulos do runtime.
5. Gera `target/distribuicao/LinkSQL/LinkSQL.exe` e, por padrão, `LinkSQL-3.3.1-portatil.zip`.
6. Opcionalmente, quando detecta WiX, tenta gerar instalador EXE com atalhos de menu/desktop e escopo por usuário. Se WiX faltar, o portátil continua disponível.

O script prevê parâmetros PowerShell `-SomentePortatil` e `-SemZip`; use diretamente o `.ps1` para passá-los caso necessário.

## Arquivos importantes

| Arquivo                                          | Papel                                                                        |
| ------------------------------------------------ | ---------------------------------------------------------------------------- |
| `pom.xml`                                        | Nome do artefato, dependências, release Java 17                              |
| `distribuicao/gerar-windows.bat`                 | Inicia a geração com PowerShell                                              |
| `distribuicao/gerar-windows.ps1`                 | Pré-requisitos, compilação e jpackage                                        |
| `distribuicao/LinkSQL.ico` (opcional)            | Ícone utilizado **quando o arquivo existir**; esta etapa não cria ícone novo |
| `target/distribuicao/LinkSQL/`                   | Aplicação portátil                                                           |
| `target/distribuicao/LinkSQL-3.3.1-portatil.zip` | ZIP portátil produzido pelo script                                           |
