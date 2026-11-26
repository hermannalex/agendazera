// Planos e preços. Os preços podem ser trocados pelas variáveis PRICE_BASIC e PRICE_PRO na Vercel.
const PLANS = {
  basic: {
    reason: 'Agendazera Basic - assinatura mensal',
    price: () => Number(process.env.PRICE_BASIC || '9.90'),
  },
  pro: {
    reason: 'Agendazera Pro - assinatura mensal',
    price: () => Number(process.env.PRICE_PRO || '49.90'),
  },
};

// Cada assinatura guarda "<deviceId>:<plano>" no external_reference do Mercado Pago.
const reference = (deviceId, plan) => `${deviceId}:${plan}`;

module.exports = { PLANS, reference };
