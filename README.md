# 👁️ Olhos da Vó — Gemini Live + Câmera em 1 Clique

Aplicativo Android de acessibilidade criado para idosos e pessoas com baixa visão severa. Com **1 toque em qualquer lugar da tela** (ou **0 toques**, iniciando automaticamente ao tocar no ícone do app), ele:

1. **Vibra e avisa por voz** em português (*"Abrindo câmera do Gemini, aguarde"*).
2. **Abre o aplicativo oficial do Google Gemini** (usando a conta gratuita normal, sem custos de API).
3. **Clica automaticamente no botão `Gemini Live`** e, em seguida, **clica automaticamente no botão de `Ativar Câmera`** usando um Serviço de Acessibilidade do Android (`AccessibilityService`).
4. **Dá uma vibração dupla na mão dela** avisando que a câmera já está aberta e que ela já pode falar e apontar o celular!

---

## 📦 Como gerar o arquivo `.APK` para instalar no celular dela

Você tem duas formas de gerar o instalador `.apk`:

### Forma A: Pelo GitHub (Sem precisar instalar Android Studio no PC)
1. Suba este repositório para uma conta no GitHub.
2. O arquivo [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) vai compilar o aplicativo automaticamente na aba **Actions** do GitHub em cerca de 2 minutos.
3. Clique no workflow concluído e baixe o arquivo **`OlhosDaVo-APK`** (`app-debug.apk`) direto no celular da sua avó.

### Forma B: Pelo Android Studio (No seu computador)
1. Abra a pasta `noble-bell` no **Android Studio**.
2. No menu superior, clique em **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. O arquivo `.apk` será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📱 Como configurar no celular da sua avó (Uma única vez)

1. **Verifique o app oficial do Gemini:**
   Certifique-se de que o app oficial **Google Gemini** está instalado na Play Store no celular dela e que você já abriu o **Gemini Live com câmera** pelo menos uma vez manualmente para aceitar as permissões iniciais do próprio Google.
2. **Instale o `Olhos da Vó` (`.apk`):**
   Abra o aplicativo **Olhos da Vó** e toque no aviso vermelho para entrar nas **Configurações da Família** (ou segure o dedo por 2 segundos no botão discreto `⚙️ Família (Segure)` no canto superior direito).
3. **Ative o Serviço de Acessibilidade:**
   * No **Passo 1**, toque em **"Abrir Configurações de Acessibilidade"** e ative **Olhos da Vó (Atalho Gemini Live)**.
   * ⚠️ **Importante (Android 13, 14 e 15):** Por segurança do Android em apps instalados fora da Play Store, se aparecer a mensagem *"Configuração restrita"*, volte na tela do nosso app, toque no botão **"Abrir Dados do App (Liberar Config. Restrita)"**, toque nos **3 pontinhos (`⋮`)** no canto superior direito da tela do sistema e escolha **"Permitir configurações restritas"**. Depois volte e ative a Acessibilidade normalmente.
4. **Escolha o modo preferido dela (Passo 2):**
   * **Botão Gigante (1 clique):** A tela inteira fica preta com borda amarela de alto contraste. Ela pode tocar em qualquer ponto da tela.
   * **Iniciar direto ao abrir o ícone (0 cliques):** Marque a opção *"Iniciar direto ao abrir o ícone do app"*. Assim, basta ela tocar no ícone do app na tela inicial do Android que ele já abre o Gemini Live com câmera direto!
5. **Teste pelo botão verde `▶ TESTAR AUTOMAÇÃO AGORA`:**
   * O app procura os botões **Live** e **Câmera** pelo nome automaticamente.
   * Caso o celular dela tenha alguma diferença de posição na tela, você pode ajustar as barras de posição X% / Y% no **Passo 3** das Configurações da Família.
