# SantySQL Studio 2.2 — Gerar executável e instalador para Windows

## O que será gerado

A versão 2.2 permite montar dois formatos a partir do projeto Maven atual:

1. **Portátil:** uma pasta `SantySQL-Studio` contendo `SantySQL-Studio.exe`, bibliotecas e um Java embutido. Pode ser copiada para outro Windows 10/11 x64, sem Maven e sem instalação prévia de Java. A pasta inteira deve ser preservada; não copie apenas o `.exe`.
2. **Instalador:** um arquivo `SantySQL-Studio-2.2.0.exe` (nome pode variar) para instalar no perfil do usuário, com atalhos na área de trabalho e no menu Iniciar. Requer **WiX Toolset** durante a **geração do instalador**, não no computador de destino.

**Importante:** `jpackage` gera pacotes para o sistema operacional onde é executado. Portanto, o `.exe` deve ser gerado **no Windows**. Este ZIP incremental contém os scripts e o código, **não** um `.exe` pré-compilado.

## 1. Requisitos somente no computador que gera o instalador

- Windows 10 ou 11 **64 bits**.
- **JDK 17 ou superior, 64 bits**, com `jpackage.exe` e `jar.exe`. Se você já utiliza Java 17 para desenvolver, prefira mantê-lo para compatibilidade.
- Apache Maven funcionando no PATH.
- Internet para o Maven baixar dependências ainda não armazenadas em cache.
- Para gerar o instalador: **WiX Toolset** compatível com o JDK (com `candle.exe` e `light.exe` no PATH para WiX 3, ou `wix.exe` em versões compatíveis do JDK).

Verifique no PowerShell:

```powershell
java -version
mvn -version
jpackage --version
where.exe candle
where.exe light
```

A ausência do WiX **não impede** gerar a pasta portátil com executável e Java integrado.

## 2. Gerar a distribuição

**Mais simples:** dê dois cliques em `distribuicao\gerar-windows.bat` (ou execute a partir da raiz):

```powershell
.\distribuicao\gerar-windows.bat
```

O script executa a compilação Maven, copia as dependências de runtime e cria a imagem nativa pelo `jpackage`, com os módulos Java exigidos pelo Swing e JDBC. Se detectar WiX, tenta também gerar o instalador `.exe`. A versão portátil é gerada **mesmo sem WiX**.

O resultado estará em:

```text
target\distribuicao\
  SantySQL-Studio\
    SantySQL-Studio.exe
    app\...
    runtime\...
  SantySQL-Studio-2.2.0-portatil.zip
  SantySQL-Studio-2.2.0.exe         (somente com WiX)
```

Para gerar apenas a versão portátil (sem exigir WiX):

```powershell
.\distribuicao\gerar-windows.bat -SomentePortatil
```

Para não comprimir a pasta portátil em ZIP, inclua `-SemZip`.

**Não execute `mvn clean` após a geração**, pois essa limpeza remove a pasta `target` e os instaladores gerados.

## 3. Instalar em outro computador

**Com instalador:** leve o `.exe` de instalação ao outro Windows e execute-o. Os atalhos devem ser criados pelo instalador.

**Portátil:** extraia `SantySQL-Studio-2.2.0-portatil.zip` em uma pasta e execute:

```text
SantySQL-Studio\SantySQL-Studio.exe
```

O Java está na subpasta `runtime` da distribuição. **O computador de destino não precisa ter Java nem Maven.**

O aplicativo usa o mesmo banco remoto MySQL dos servidores cadastrados, portanto a máquina de destino precisa de acesso à rede/VPN e permissão de conexão aos bancos.

## 4. Conexões e senha no aplicativo instalado

No modo empacotado, o arquivo de conexões fica no perfil do Windows:

```text
%USERPROFILE%\.santysql-studio\config\connections.json
```

É criado automaticamente com lista vazia na primeira abertura. Acesse **Conexões > Gerenciar conexões** para cadastrar suas conexões e depois **Carregar conexões**.

Se já utiliza um JSON de conexões no projeto anterior, pode copiá-lo para esse novo caminho, por exemplo (ajuste a origem):

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.santysql-studio\config"
Copy-Item ".\config\connections.json" "$env:USERPROFILE\.santysql-studio\config\connections.json" -Force
```

**O instalador não copia configurações de clientes nem senhas** para outras máquinas.

A partir da versão 2.2, ao clicar **Carregar conexões**:

- Se a variável de ambiente `passwordEnv` estiver disponível, ela continua sendo utilizada normalmente.
- Se a variável estiver ausente, o aplicativo solicita a senha em uma janela própria.
- A senha não é escrita no JSON, no histórico ou nos logs; permanece somente na sessão atual da aplicação.

> Atenção: se cada servidor usar uma senha diferente, configure nomes `passwordEnv` distintos no JSON. Conexões que compartilham o mesmo nome de variável reutilizam a mesma senha durante uma carga.

## 5. Arquivos do usuário preservados

O instalador e o portátil reutilizam os arquivos locais já existentes no perfil:

```text
%USERPROFILE%\.santysql-studio\sql-session.json
%USERPROFILE%\.santysql-studio\sql-history.txt
%USERPROFILE%\.santysql-studio\logs\
```

As preferências de tema, paginação e autocomplete permanecem no registro de preferências Java do usuário. A gravação de novos `.sql` e `.csv` sugere a pasta `Documentos` (quando existente), em vez da pasta de instalação.

## 6. Ícone personalizado (opcional)

Coloque seu próprio arquivo **`distribuicao\SantySQL-Studio.ico`** antes de rodar o script. O `jpackage` o utilizará automaticamente. Sem esse arquivo, é usado o ícone padrão do pacote. Para instalação ampla, também é recomendável assinar digitalmente o instalador com um certificado de assinatura de código.
