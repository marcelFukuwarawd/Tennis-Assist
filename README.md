# Court Vision AI

Aplicativo Android **gratuito, privado e 100% offline** para gravar e analisar partidas e treinos de
**tênis e pickleball** com a câmera do celular: placar, chamadas de linha (dentro/fora/inconclusivo),
rastreamento da bola e dos jogadores, estatísticas, mapas de calor, treinador local, biomecânica 2D,
editor de melhores momentos e treino de precisão.

Sem login, sem cadastro, sem assinatura, sem anúncios, **sem permissão de internet**.
Implementação independente, inspirada em funcionalidades de apps comerciais, sem usar código, modelos,
marcas ou recursos de terceiros protegidos.

> Status: versão inicial (0.1.0). Veja o que está pronto, parcial ou pendente em
> [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md). A arbitragem é **assistência recreativa**,
> não um sistema oficial certificado.

---

## Como gerar o APK pelo GitHub (passo a passo, sem precisar de programação)

O repositório já tem uma "receita" automática (`.github/workflows/build-android.yml`). Sempre que
houver uma alteração no código, o próprio GitHub monta o aplicativo num computador dele e entrega o
arquivo `.apk` pronto para baixar. Você não precisa instalar nada no seu computador.

### Parte 1 – Pedir para o GitHub fabricar o APK

**Jeito automático (não precisa fazer nada):** toda vez que um código novo é enviado ao GitHub, a
fabricação do APK começa sozinha. Basta ir à aba **Actions** do repositório e esperar.

**Para ter o botão "fabricar agora" (Run workflow), faça uma única vez:** o GitHub só mostra esse
botão quando a receita está no ramo principal (`main`). Para levar o código para o `main`:

1. Abra o repositório **Tennis-Assist** no GitHub.
2. Clique na aba **Pull requests** e no botão verde **New pull request**.
3. Em **base**, deixe `main`. Em **compare**, escolha `claude/sweet-faraday-si42tu`.
4. Clique em **Create pull request**, depois em **Create pull request** de novo e, por fim, em
   **Merge pull request → Confirm merge**.

Depois disso, sempre que quiser um APK novo:

1. Clique na aba **Actions** (ícone de "play", no menu de cima do repositório).
   - Se aparecer um aviso perguntando se você quer habilitar os workflows, clique no botão verde.
2. Na lista da esquerda, clique em **Build Android APK**.
3. À direita, clique em **Run workflow**, deixe o ramo `main` e clique no botão verde **Run workflow**.
4. Aguarde. Aparece uma linha com um círculo amarelo girando ("trabalhando"). Leva cerca de 10 a 20
   minutos. Quando terminar, o círculo vira um **✓ verde**.
   - Se aparecer um **✗ vermelho**, clique na linha para ver qual etapa falhou.

### Parte 2 – Baixar o APK

1. Clique na linha que ficou com o **✓ verde**.
2. Role a página até o final, na seção **Artifacts**.
3. Clique em **CourtVisionAI-0.1.0-build-N** (o número muda). Será baixado um arquivo `.zip`.
4. Abra o `.zip`. Dentro dele está o arquivo **CourtVisionAI-debug.apk** (e o arquivo
   `SHA256SUMS.txt`, que serve só para conferir que o arquivo não foi alterado).

> Dica: faça o download direto pelo navegador do celular para pular a etapa de transferir o arquivo.
> Os artefatos ficam disponíveis por 90 dias.

### Parte 3 – Instalar no celular Android

1. Passe o arquivo `CourtVisionAI-debug.apk` para o celular (ou baixe direto nele).
2. Toque no arquivo. O Android vai dizer que, por segurança, não permite instalar apps de fontes
   desconhecidas. Toque em **Configurações** e ative **Permitir desta fonte** para o app que você
   está usando para abrir o arquivo (Chrome, Arquivos etc.).
3. Volte e toque em **Instalar**. Se o Google Play Protect mostrar um aviso, toque em
   **Mais detalhes → Instalar mesmo assim** (isso acontece com qualquer app que não veio da loja).
4. Abra o **Court Vision AI**. Pronto — não há cadastro.

Requisitos: Android 12 ou mais recente, processador de 64 bits (ARM64 – praticamente todos os
celulares desde 2017).

### Parte 4 (opcional) – Versão "release" assinada e versões oficiais

A versão *debug* funciona normalmente para uso pessoal. Para uma versão final assinada
(`CourtVisionAI-release-arm64.apk`, menor e mais rápida), o GitHub precisa de uma "chave de
assinatura" guardada em segredo:

1. Crie uma chave uma única vez (alguém com Java instalado roda):
   `keytool -genkeypair -keystore courtvision.jks -alias courtvision -keyalg RSA -keysize 2048 -validity 10000`
   e depois `base64 -w0 courtvision.jks > chave.txt`. **Guarde o arquivo `.jks` e as senhas em local seguro.**
2. No repositório: **Settings → Secrets and variables → Actions → New repository secret** e crie:
   - `CV_KEYSTORE_BASE64` = conteúdo do arquivo `chave.txt`
   - `CV_KEYSTORE_PASSWORD` = senha do arquivo
   - `CV_KEY_ALIAS` = `courtvision`
   - `CV_KEY_PASSWORD` = senha da chave
3. Rode o workflow de novo: o pacote passará a trazer também o APK release.
4. Para publicar uma versão oficial na página **Releases**, crie uma *tag* começando com `v`
   (por exemplo `v0.1.0`) em **Releases → Draft a new release → Choose a tag**. O GitHub monta e
   anexa os APKs automaticamente.

As senhas **nunca** ficam no código: apenas nos "Secrets" do GitHub.

---

## Primeiro uso do aplicativo

1. Na tela **Início**, toque em **Gravar partida**. Escolha tênis ou pickleball, simples ou duplas, e
   (se quiser) os nomes. Toque em **Começar a gravar**.
2. A câmera abre em tela cheia, deitada. Coloque o celular atrás da linha de fundo, alto (tripé ou
   grade a 2–4 m), com a quadra inteira visível, e siga as dicas na tela.
3. Toque no ícone de moldura para **calibrar**: arraste os círculos numerados até os cantos externos
   da quadra (a lupa mostra o detalhe) e toque em **Ajuste fino automático**.
4. Toque no botão vermelho para gravar. Use **+ A / + B** para marcar pontos; logo depois aparecem
   botões para dizer como o ponto terminou (winner, erro não forçado, erro forçado, ace, dupla falta).
5. Ao parar, a análise começa sozinha. Abra a sessão em **Partidas**: a aba **Resumo** mostra o placar
   e as estatísticas principais; **Pontos** permite corrigir e classificar cada ponto com um toque;
   **Vídeo** tem tela cheia, câmera lenta e quadro a quadro; **Mais** tem mapa, treinador, editor e exportação.

Também é possível **Importar vídeo** de qualquer câmera (Android, iPhone, GoPro, DJI...).

## Para desenvolvedores

```bash
./tools/verify_models.sh      # confere SHA-256 dos modelos de IA
./gradlew test                # testes unitários e de integração (JVM)
./gradlew lintDebug
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requisitos locais: JDK 17+, Android SDK (platform 35, build-tools 35.0.0). Textos da interface:
edite `tools/strings.tsv` e rode `python3 tools/gen_strings.py` (gera inglês e português).

Documentos: [ARCHITECTURE.md](ARCHITECTURE.md) · [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) ·
[COMMUNITY_RESEARCH.md](COMMUNITY_RESEARCH.md) · [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) ·
[CHANGELOG.md](CHANGELOG.md)

Licença: MIT (veja [LICENSE](LICENSE)). Os modelos de IA incluídos são Apache-2.0.
