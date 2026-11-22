# Controle Universal Smart Pro — edição com protocolos reais

Projeto Android nativo em Kotlin + Jetpack Compose. Requer JDK 17, Android SDK 35 e Gradle 8.9.

## O que foi implementado nesta edição

- **Roku:** conexão manual por IP ao Roku ECP na porta 8060. Consulta `/query/device-info` para confirmar a conexão e envia teclas reais por `POST /keypress/...`. O Roku e o telefone precisam estar na mesma rede; o Roku deve permitir controle por aplicativos móveis/rede.
- **Samsung Smart TV:** conexão WebSocket no endpoint de controle remoto da Samsung (porta 8001). O usuário deve aceitar a solicitação de controle na TV quando exibida. O token recebido é guardado localmente e comandos de navegação, volume, canais e mídia são enviados pelo canal remoto. Compatibilidade varia por modelo/firmware e configurações de IP Remote.
- **LG webOS:** conexão WebSocket SSAP (porta 3000), solicitação de registro e armazenamento local do client-key depois da aprovação na TV. Nesta edição foram ligados comandos SSAP de volume e desligamento. O controle completo de navegação requer o canal pointer/input e varia conforme versão webOS.
- **Bluetooth:** atalho para configurações Bluetooth do Android; não simula um controle HID universal.
- **Infravermelho:** detecta se o telefone anuncia emissor IR. Não transmite códigos genéricos porque os códigos dependem do fabricante/modelo e o Samsung Galaxy A17 normalmente não possui emissor IR integrado.

## Ainda não implementado

- Android TV / Google TV: protocolo Remote v2 com pareamento por código e TLS mútuo.
- Fire TV: ADB pela rede com geração/armazenamento de chave e autorização na TV.
- Descoberta automática de todos os modelos, teclado Bluetooth HID e base de códigos IR por modelo.
- Controle completo do webOS (D-pad pelo pointer socket) e compatibilidade com todos os modelos Samsung.

**Importante:** não existe protocolo universal para todas as TVs. O funcionamento depende do modelo, firmware, rede local e autorização na própria TV. O app informa explicitamente quando um protocolo ainda não está disponível.

## Compilar APK

### Pelo GitHub Actions
1. Crie um repositório GitHub.
2. Envie o conteúdo desta pasta para a raiz do repositório (a pasta `app`, `build.gradle.kts`, `settings.gradle.kts` e `.github`).
3. Abra a aba **Actions** e execute **Compilar Controle Universal Smart Pro**.
4. Quando terminar com marca verde, abra a execução e baixe o artefato `ControleUniversalSmartPro-debug`.
5. Extraia o ZIP do artefato e instale `app-debug.apk` no Android.

### Localmente
No diretório do projeto: `gradle assembleDebug`. O APK será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Uso
1. Conecte o celular e a TV à mesma rede Wi-Fi.
2. Veja o endereço IP da TV nas configurações de rede dela.
3. Escolha a marca no app, digite o IP e toque em **Conectar à TV**.
4. Em Samsung/LG, aceite a solicitação de controle na tela da TV, se aparecer.
5. Teste os botões depois que o app indicar conexão ativa.
