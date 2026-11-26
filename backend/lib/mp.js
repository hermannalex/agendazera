// Funções compartilhadas: acesso à API do Mercado Pago e validação da chave do app.
const MP_API = 'https://api.mercadopago.com';

function checkAppKey(req) {
  const expected = process.env.APP_KEY;
  if (!expected) return true; // sem APP_KEY configurada, não exige chave
  return req.headers['x-app-key'] === expected;
}

async function mp(path, options = {}) {
  const token = process.env.MP_ACCESS_TOKEN;
  if (!token) throw new Error('MP_ACCESS_TOKEN não configurado no servidor');
  const res = await fetch(MP_API + path, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.message || `Mercado Pago respondeu ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function validDeviceId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{4,80}$/.test(id);
}

module.exports = { mp, checkAppKey, validDeviceId };
