# Agendazera: robô de WhatsApp para agendamento

App Android que responde automaticamente às mensagens do **WhatsApp** ou do **WhatsApp Business** no próprio celular da clínica e agenda as consultas.

## Como funciona

O app usa o **acesso às notificações** do Android (`NotificationListenerService`):

1. Chega uma mensagem no WhatsApp e o Android mostra a notificação.
2. O app lê o nome do contato e o texto e passa os dois para o robô.
3. O robô responde pelo botão **"Responder"** da própria notificação, o mesmo que você usa na tela de bloqueio.

Ele **não usa a API oficial** do WhatsApp nem precisa de servidor ou internet extra. Tudo fica num banco de dados local (Room) no celular.

### Conversa do robô

```
Paciente: oi
Robô: Olá! 👋 Você está falando com o atendimento automático da *Clínica X*.
      *1* - Agendar consulta
      *2* - Minhas consultas
      *3* - Cancelar consulta
      *4* - Falar com atendente
Paciente: 1
Robô: Qual o nome completo do paciente?
Paciente: Maria da Silva
Robô: Escolha o dia:  *1* - Seg 24/11  *2* - Ter 25/11 ...
Paciente: 1
Robô: Horários livres em Seg 24/11:  *1* - 08:00  *2* - 08:30 ...
Paciente: 2
Robô: Confirma a consulta de Maria Da Silva para Segunda-feira, 24/11 às 08:30?  (S/N)
Paciente: s
Robô: ✅ Consulta confirmada! ...
```

- Mandar **menu** ou **0** volta ao início a qualquer momento.
- A opção **4** (atendente) silencia o robô para aquele contato pelo tempo definido nas configurações. Na tela Agenda aparece "Aguardando atendente", com o botão **Devolver ao robô**.
- Depois de 2 h sem mensagens, a conversa recomeça do menu. O nome do paciente fica guardado para a próxima vez.
- O robô ignora mensagens de **grupos**.

### Telas

- **Agenda**: consultas do dia, com navegação entre dias, liga/desliga do robô, cancelamento e o botão **+** para agendar manualmente (por telefone ou no balcão).
- **Configurações**: nome e endereço da clínica, dias de atendimento, horários, almoço, duração da consulta, até quantos dias à frente aceitar marcação, antecedência mínima e tempo de pausa para o atendente.

## Planos

| | **Basic** · R$ 9,90/mês | **Pro** · R$ 49,90/mês |
|---|---|---|
| Robô de agendamento no WhatsApp | ✅ | ✅ |
| Agenda Google vinculada | ✅ | ✅ (uma por profissional) |
| Profissionais atendendo pelo robô | 1 | Ilimitados, cada um com seus dias, horários, intervalo e duração |
| Serviços com duração e preço | — | ✅ (ex.: corte 40 min R$ 50, consulta 30 min) |
| Cliente escolhe o profissional | — | ✅, ou "sem preferência" (primeiro horário livre) |

- **30 dias de teste grátis** com todos os recursos do Pro. Depois disso, sem plano ativo, o robô para de responder no WhatsApp. A agenda e a equipe continuam acessíveis no app.
- No plano Basic, se houver mais de um profissional cadastrado, só o primeiro atende pelo robô, e os serviços são ignorados.
- A assinatura é feita em **Configurações → Assinatura**: o cliente informa o e-mail da conta Mercado Pago e escolhe Basic ou Pro. A cobrança mensal no cartão é automática. Ao mudar do Basic para o Pro, o servidor cancela o Basic sozinho.
- O token do Mercado Pago **não fica no app**: ele fica no servidor da pasta `backend/` (veja `backend/README.md`). Enquanto `BILLING_API_URL` em `app/build.gradle.kts` estiver como `SEU-PROJETO`, a cobrança fica desligada e tudo funciona como Pro (modo desenvolvimento).

## Equipe (profissionais e serviços)

Na aba **Equipe**:
- **Profissionais**: nome, especialidade, dias, horário de início e fim, intervalo, duração do atendimento e agenda Google própria. Profissionais com consultas no histórico são desativados em vez de apagados.
- **Serviços** (Pro): nome, duração, preço opcional e quem faz o serviço (nenhum marcado = todos).

Como o robô conduz a conversa no Pro: nome → serviço (se houver serviços cadastrados) → profissional (se mais de um fizer o serviço, com a opção "sem preferência") → dia → horário → confirmação. O tempo reservado é a duração do serviço ou, sem serviço, a duração padrão do profissional.

Em **Configurações → Tipo**, escolha **Clínica** (o robô fala "paciente" e "consulta") ou **Salão** (fala "cliente" e "horário").

## Agenda Google

Cada profissional pode ter uma agenda do celular vinculada (Equipe → profissional → **Vincular agenda Google**):
- cada agendamento vira um evento na agenda dele ("Serviço – Nome do cliente"), que é apagado se o agendamento for cancelado;
- com **"Compromissos da agenda bloqueiam horários"** ligado, o robô não oferece horários em que o profissional já tem compromisso; eventos de dia inteiro, como férias, bloqueiam o dia todo;
- não precisa de login nem de chave de API: o app grava na agenda do celular, e o Android sincroniza com o Google Agenda. A conta Google precisa estar adicionada ao celular, com a sincronização da Agenda ativa.

## Como compilar e instalar

1. Instale o **Android Studio** (versão Ladybug ou mais nova).
2. Abra esta pasta (`File → Open`). O Android Studio baixa o Gradle 8.9 e as dependências sozinho.
3. Conecte o celular da clínica com a depuração USB ativada e clique em **Run ▶**.
   Para gerar um APK: `Build → Build App Bundle(s) / APK(s) → Build APK(s)`.
4. No celular, abra o app e toque em **Liberar acesso**. Depois ative **Agendazera** em *Acesso às notificações*.

### Configurações importantes no celular

- **Bateria**: em *Configurações → Apps → Agendazera → Bateria*, escolha **Sem restrições**. Em Xiaomi, Samsung e Motorola, ative também o "início automático". Sem isso, o sistema pode encerrar o robô.
- **Notificações do WhatsApp** precisam estar **ligadas** e com conteúdo visível. O robô só "vê" as mensagens que geram notificação.
- **Não deixe a conversa aberta** na tela do WhatsApp: com o chat aberto, o WhatsApp não gera notificação e o robô não responde. O ideal é deixar o celular da clínica dedicado ao robô.

## Limitações (importante)

- O app depende de o WhatsApp mostrar a notificação com o botão "Responder". Isso funciona hoje, mas o WhatsApp pode mudar o comportamento numa atualização.
- O contato é identificado pelo **nome que aparece na notificação**: o nome salvo na agenda do celular ou o número, se não estiver salvo. Dois contatos salvos com o mesmo nome seriam tratados como a mesma pessoa.
- Se chegarem várias mensagens seguidas antes da resposta, só a última é lida.
- Responder automaticamente por notificação não é uma integração oficial do WhatsApp. Para um volume alto ou várias unidades, o caminho mais robusto é a **WhatsApp Business Platform (Cloud API)**, com um servidor. Dá para migrar depois reaproveitando o `BotEngine`, que não depende do Android.

## Estrutura do código

```
app/src/main/java/br/com/agendazera/
├── App.kt                      # inicializa banco, configurações e robô
├── billing/SubscriptionManager.kt  # planos Basic/Pro, teste grátis de 30 dias
├── calendar/CalendarSync.kt    # integração com a agenda do celular / Google
├── bot/
│   ├── BotEngine.kt            # fluxo da conversa (menu, agendar, cancelar…)
│   └── SlotCalculator.kt       # calcula horários livres
├── data/
│   ├── Entities.kt             # Appointment, Professional, Service, ConversationState
│   ├── AppDatabase.kt          # Room + DAOs
│   ├── AppointmentRepository.kt # cria/cancela consultas e sincroniza a agenda
│   └── Settings.kt             # configurações da clínica
├── service/
│   └── WhatsAppListenerService.kt  # lê notificações e responde
└── ui/                         # telas: Agenda, Equipe, Configurações
backend/                        # servidor de cobrança (Vercel + Mercado Pago)
```

Os testes unitários estão em `app/src/test`. Rode com `./gradlew test` ou pelo Android Studio.

Para mudar os textos do robô, edite as mensagens em `BotEngine.kt`.
