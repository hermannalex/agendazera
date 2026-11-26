// GET /api/status?deviceId=...
// Diz se o celular tem assinatura paga e de qual plano.
// Se o Pro estiver ativo e ainda existir um Basic ativo (upgrade), o Basic é cancelado
// para o cliente não pagar os dois.
const { mp, checkAppKey, validDeviceId } = require('../lib/mp');
const { reference } = require('../lib/plans');

const byNewest = (a, b) => new Date(b.date_created) - new Date(a.date_created);

async function search(ref) {
  const data = await mp(`/preapproval/search?external_reference=${encodeURIComponent(ref)}&limit=50`);
  return (data.results || []).sort(byNewest);
}

module.exports = async (req, res) => {
  if (req.method !== 'GET') return res.status(405).json({ error: 'Use GET' });
  if (!checkAppKey(req)) return res.status(401).json({ error: 'Chave do app inválida' });

  const deviceId = req.query.deviceId;
  if (!validDeviceId(deviceId)) return res.status(400).json({ error: 'deviceId inválido' });

  try {
    // deviceId sozinho = assinaturas da primeira versão (só existia o Basic)
    const [pro, basic, legacy] = await Promise.all([
      search(reference(deviceId, 'pro')),
      search(reference(deviceId, 'basic')),
      search(deviceId),
    ]);
    const basicAll = [...basic, ...legacy].sort(byNewest);
    const proActive = pro.find((p) => p.status === 'authorized');
    const basicActive = basicAll.filter((p) => p.status === 'authorized');

    if (proActive && basicActive.length) {
      await Promise.all(basicActive.map((p) =>
        mp(`/preapproval/${p.id}`, { method: 'PUT', body: JSON.stringify({ status: 'cancelled' }) })
          .catch(() => null)));
    }

    const active = proActive || basicActive[0];
    const latest = active || [...pro, ...basicAll].sort(byNewest)[0];
    return res.status(200).json({
      active: Boolean(active),
      plan: proActive ? 'pro' : basicActive.length ? 'basic' : null,
      status: latest ? latest.status : 'none',
      nextPaymentDate: active ? active.next_payment_date || null : null,
    });
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }
};
