# 08. Segurança, dados locais e compatibilidade

## Onde os dados ficam

| Arquivo ou serviço                                             | Conteúdo                                                            | Cuidados                                                                   |
| -------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `%USERPROFILE%\.linksql\config\connections.json`       | Aliases, banco, host, porta, schema, usuário e parâmetros JDBC      | Pode conter informações internas de infraestrutura; não distribuir         |
| `%USERPROFILE%\.linksql\config\credentials.dpapi.json` | Material criptografado por DPAPI (escopo CurrentUser Windows)       | Não editar à mão nem publicar; depende do usuário Windows                  |
| `%USERPROFILE%\.linksql\sql-session.json`              | SQL em texto, arquivos associados, seleção de abas e conexão padrão | Pode conter nomes de clientes/tabelas e filtros sensíveis; proteger backup |
| `%USERPROFILE%\.linksql\sql-history.txt`               | Consultas codificadas em Base64                                     | **Base64 não é criptografia**; tratar como conteúdo sensível               |
| `%USERPROFILE%\.linksql\sql-favorites.txt`             | SQL favorito em Base64                                              | Mesmos cuidados do histórico                                               |
| Preferências Java                                              | Tema, paginação, fonte e parâmetros de editor                       | Podem estar no mecanismo `java.util.prefs`, conforme o SO                  |
| Logs locais                                                    | Diagnósticos operacionais                                           | Conferir conteúdo antes de compartilhar com terceiros                      |

## Senhas protegidas no Windows

`ConnectionVault` utiliza **Windows DPAPI / CurrentUser** para armazenar segredos protegidos. Um processo executado por outro usuário Windows não deve conseguir usar automaticamente o mesmo segredo. No LinkSQL JDBC 0.5, a integração validada pelo usuário reutiliza o cofre quando o desktop e o exportador executam na mesma conta Windows.

O uso da API é específico de Windows; em ambientes sem DPAPI, não se deve improvisar um arquivo de senhas em texto puro. Perfis também podem referenciar variável de ambiente de senha conforme o funcionamento do registro, com a responsabilidade de proteger essas variáveis.

## Limites do modo somente leitura

**Não considere `readOnly=true` no JSON garantia contra ALTER, DELETE ou UPDATE no editor desktop.** O código atual não aplica esse campo como controle de permissão universal em todas as conexões. A execução de script utiliza `Statement.execute` e pode enviar comandos além de SELECT, dependendo do texto e do usuário JDBC.

Para trabalho em servidores de produção:

1. Use conta com privilégios **SELECT** e acesso somente aos schemas necessários.
2. Não use root/administrador para consultas operacionais.
3. Separe ambiente de testes e de produção com aliases claros.
4. Faça backup e obtenha autorização antes de qualquer alteração de dados.
5. Não execute scripts recebidos de terceiros sem revisão.

O driver embutido possui uma guarda própria (`ReadOnlySqlGuard`), mas isso não substitui grants do SGBD.

## Riscos específicos de grandes consultas

- JOIN federado pode materializar dados volumosos localmente e consumir memória na JVM.
- Contagem `COUNT(*)` em grandes JOINs pode executar trabalho caro, mesmo em thread auxiliar.
- A escolha de `Todas (1 página)` pode exigir muita memória; prefira limites e paginação quando adequado.
- Transmitir dados pessoais para exportação CSV cria novo arquivo local, com necessidade de gestão de acesso e retenção.


## Migração do diretório e compatibilidade com o JDBC 0.5

A partir da 3.3.1, o diretório principal passa a ser `%USERPROFILE%\.linksql`. O primeiro uso copia os arquivos conhecidos de `%USERPROFILE%\.santysql-studio` **sem sobrescrever arquivos já presentes no novo diretório e sem apagar o antigo**. Um marcador impede reimportações inesperadas se o usuário apagar algum dado depois da migração.

Enquanto o SantySQL 5.3.7 utilizar o **LinkSQL JDBC 0.5**, os arquivos **`config/connections.json`** e **`config/credentials.dpapi.json`** são também sincronizados com a pasta antiga, para preservar a integração. Não são duplicadas senhas em texto puro. A leitura DPAPI exige a mesma conta Windows; copiar o JSON para outro usuário não permite descriptografar as senhas.

As preferências Java são importadas da localização antiga para `br.com.linksql` apenas quando a chave ainda não existe. O código-fonte do **LinkSQL JDBC 0.6** foi adaptado para preferir `.linksql`, mas um novo JAR ainda precisa ser gerado, testado e instalado manualmente. Não remova o JDBC 0.5 validado até lá.

**Antes de atualizar:** feche as aplicações, faça backup dos dois diretórios e valide a importação. Depois da validação, mantenha o diretório antigo enquanto o JDBC 0.5 ainda estiver em uso.
