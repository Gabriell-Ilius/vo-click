# 📱 Guia Completo de Instalação para a Família

Este guia explica passo a passo como instalar e configurar o aplicativo **Olhos da Vó** no celular Android da sua avó. Você só precisará fazer essa configuração **uma única vez**.

---

## Pré-requisito: Preparar o App Oficial do Gemini
Antes de configurar o atalho, garanta que o aplicativo oficial **Google Gemini** já está pronto:
1. Abra a **Play Store** no celular dela e instale o **Google Gemini** (caso ainda não esteja instalado).
2. Abra o **Google Gemini** manualmente uma vez, toque no botão **Live** (canto inferior direito, ícone de ondas de voz com brilho) e depois toque no ícone da **Câmera**.
3. Aceite as permissões de **Microfone** e **Câmera** que o próprio aplicativo do Google pedir.

---

## Passo 1: Baixar e Instalar o `OlhosDaVo.apk`
1. No navegador do celular dela (Chrome), acesse a página de lançamentos do projeto:
   👉 **[github.com/Gabriell-Ilius/vo-click/releases/latest](https://github.com/Gabriell-Ilius/vo-click/releases/latest)**
2. Em **Assets**, toque em **`OlhosDaVo.apk`** para baixar.
3. Abra o arquivo baixado e confirme a instalação.
   * *Se o Google Play Protect perguntar se deseja instalar mesmo assim (por ser um aplicativo caseiro fora da Play Store), toque em **"Mais detalhes" → "Instalar assim mesmo"**.*

---

## Passo 2: Liberar e Ativar o Serviço de Acessibilidade

Para que o nosso aplicativo consiga clicar sozinho nos botões **Live** e **Câmera** por ela, ele utiliza o recurso oficial de **Acessibilidade do Android**.

1. Abra o aplicativo **Olhos da Vó** e toque na faixa vermelha de aviso (ou segure o dedo por 2 segundos no botão `⚙️ Família (Segure)` no canto superior direito).
2. Toque no botão amarelo **"Abrir Configurações de Acessibilidade"**.
3. Procure por **Olhos da Vó (Atalho Gemini Live)** (geralmente dentro de *"Aplicativos instalados"* ou *"Serviços transferidos por download"*) e ative a chave.

### ⚠️ Como resolver o aviso de "Configuração Restrita" (Android 13, 14 e 15)
Nos celulares com Android 13 ou mais recente, o sistema bloqueia inicialmente serviços de acessibilidade de aplicativos instalados via `.apk`. Se aparecer uma janela dizendo **"Configuração restrita"**:

1. Toque em **OK** nessa janelinha e volte para a tela de **Configurações da Família** dentro do app **Olhos da Vó**.
2. Toque no botão cinza **"Abrir Dados do App (Liberar Config. Restrita)"**.
3. Na tela de informações do aplicativo que o Android abrir, olhe no **canto superior direito** e toque nos **3 pontinhos (`⋮`)**.
4. Toque em **"Permitir configurações restritas"** e confirme com a senha/digital do celular.
5. Pronto! Agora volte em **"Abrir Configurações de Acessibilidade"** e ative o **Olhos da Vó (Atalho Gemini Live)** normalmente.

---

## Passo 3: Escolher o Modo de Uso para Ela

Ainda na tela de **Configurações da Família** (Passo 2):

* **Modo Botão Gigante (1 Clique):** Deixe desmarcada a primeira opção. Ao abrir o app, a tela inteira será um único botão gigante preto e amarelo. Basta ela tocar em qualquer lugar da tela.
* **Modo Direto (0 Cliques Extras):** Marque a opção **"Iniciar direto ao abrir o ícone do app"**. Assim, quando ela tocar no ícone **Olhos da Vó** na tela inicial do celular, ele já vai falar e abrir o Gemini Live com câmera imediatamente!

---

## Passo 4: Testar e Ajustar Posições (Se Necessário)

Toque no botão verde **`▶ TESTAR AUTOMAÇÃO AGORA`**.

* O aplicativo vai abrir o Gemini, aguardar ~1 segundo, clicar no botão **Live**, aguardar ~2 segundos e clicar no botão **Câmera**, dando **duas vibrações curtas** quando terminar.
* **E se no celular dela algum botão não for clicado?**
  O app procura os botões pelo nome automaticamente, mas também possui um sistema de clique por posição (`X%`, `Y%`). Basta ajustar as barrinhas no **Passo 3** da tela de configurações se o botão da câmera ficar em uma posição diferente na tela do aparelho dela.
