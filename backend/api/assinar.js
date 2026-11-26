// POST /api/assinar  { deviceId, email, plan: "basic" | "pro" }
// Cria uma assinatura mensal (sem plano associado) no Mercado Pago e devolve o link de pagamento.
const { mp, checkAppKey, validDeviceId } = require('../lib/mp');
const { PLANS, reference } = require('../lib/plans');

module.exports = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Use POST' });
  if (!checkAppKey(req)) return res.status(401).json({ error: 'Chave do app inválida' });

  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : req.body || {};
  const { deviceId, email } = body;
  const plan = body.plan || 'basic';
  if (!validDeviceId(deviceId)) return res.status(400).json({ error: 'deviceId inválido' });
  if (!PLANS[plan]) return res.status(400).json({ error: 'Plano inválido' });
  if (typeof email !== 'string' || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    return res.status(400).json({ error: 'E-mail inválido' });
  }

  try {
    const data = await mp('/preapproval', {
      method: 'POST',
      body: JSON.stringify({
        reason: PLANS[plan].reason,
        external_reference: reference(deviceId, plan),
        payer_email: email,
        auto_recurring: {
          frequency: 1,
          frequency_type: 'months',
          transaction_amount: PLANS[plan].price(),
          currency_id: 'BRL',
        },
        back_url: process.env.BACK_URL || 'https://www.mercadopago.com.br',
        status: 'pending',
      }),
    });
    return res.status(200).json({ id: data.id, init_point: data.init_point, plan });
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }
};
