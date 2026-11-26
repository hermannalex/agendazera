# Servidor de cobrança (Mercado Pago)

Servidor pequeno que guarda o **Access Token** do Mercado Pago, para que o token nunca fique dentro do app.
Ele tem só dois endereços:

| Endereço | O que faz |
|---|---|
| `POST /api/assinar` | Cria a assinatura mensal do plano escolhido (`basic` R$ 9,90 ou `pro` R$ 49,90) e devolve o link de pagamento do Mercado Pago |
| `GET /api/status?deviceId=…` | Diz se o celular tem assinatura paga (`authorized`) e de qual plano. Se houver Pro e Basic ativos ao mesmo tempo (upgrade), cancela o Basic |

Não precisa de banco de dados: o próprio Mercado Pago guarda as assinaturas, e cada celular é
identificado pelo campo `external_reference` (`<celular>:basic` ou `<celular>:pro`).

## Publicar na Vercel (grátis)

1. Crie uma conta em vercel.com e importe esta pasta `backend` (pelo GitHub ou com `npx vercel` dentro dela).
2. Em **Settings → Environment Variables**, crie:
   - `MP_ACCESS_TOKEN`: o Access Token de **produção** do Mercado Pago
     (mercadopago.com.br/developers → Suas integrações → Credenciais de produção).
   - `APP_KEY`: uma senha qualquer e longa. A mesma vai no app.
   - Opcionais: `PRICE_BASIC` (padrão `9.90`), `PRICE_PRO` (padrão `49.90`) e `BACK_URL`.
3. Faça o deploy e anote o endereço, por exemplo `https://agendazera-cobranca.vercel.app`.
4. No app, em `app/build.gradle.kts`, troque:
   - `BILLING_API_URL` pelo endereço da Vercel;
   - `BILLING_APP_KEY` pela mesma `APP_KEY`.
5. Compile o app de novo.

## Testar antes de cobrar de verdade

Use o Access Token de **teste** e contas de teste do Mercado Pago (comprador e vendedor de teste).
Depois troque pelo token de produção.
