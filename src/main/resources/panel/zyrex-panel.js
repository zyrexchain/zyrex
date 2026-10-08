'use strict';
let apiKey = '';
const element = id => document.getElementById(id);
async function api(path, payload) {
  const response = await fetch(path, {method: payload === undefined ? 'GET' : 'POST',
    headers: {'Content-Type': 'application/json', api_key: apiKey},
    body: payload === undefined ? undefined : JSON.stringify(payload)});
  if (!response.ok) throw new Error(await response.text());
  const text = await response.text();
  if (!text) return null;
  const result = JSON.parse(text);
  if (path === '/wallet/balances') {
    result.balanceExact = text.match(/"balance"\s*:\s*(\d+)/)?.[1] || '0';
  }
  return result;
}
function coins(nano) {
  const value = BigInt(nano);
  const whole = (value / 1000000000n).toLocaleString();
  const fraction = (value % 1000000000n).toString().padStart(9, '0').replace(/0+$/, '');
  return whole + (fraction ? '.' + fraction : '');
}
async function wallet() {
  const [status, balance, addresses] = await Promise.all([
    api('/wallet/status'), api('/wallet/balances'), api('/wallet/addresses')]);
  element('wallet').hidden = false;
  element('wallet-status').textContent = status.isInitialized
    ? (status.isUnlocked ? 'Wallet unlocked' : 'Wallet locked') : 'Wallet not initialized';
  element('balance').textContent = coins(balance.balanceExact);
  element('unlock').hidden = status.isUnlocked || !status.isInitialized;
  element('addresses').replaceChildren(...addresses.map(address => {
    const row = document.createElement('li'); row.textContent = address; return row;
  }));
}
async function action(work) {
  element('error').textContent = '';
  try { await work(); } catch (error) { element('error').textContent = error.message; }
}
element('connect').addEventListener('submit', event => {
  event.preventDefault(); apiKey = element('key').value; action(wallet);
});
element('new-address').addEventListener('click', () => action(async () => {
  await api('/wallet/deriveNextKey'); await wallet();
}));
element('unlock').addEventListener('submit', event => {
  event.preventDefault(); action(async () => {
    await api('/wallet/unlock', {pass: element('password').value});
    element('password').value = ''; await wallet();
  });
});
async function refresh() {
  await action(async () => {
    const info = await api('/info');
    element('height').textContent = info.fullHeight ?? 0;
    element('peers').textContent = info.peersCount ?? 0;
    if (info.network) element('network').textContent = 'ZYREX ' + info.network.toUpperCase();
    if (apiKey) await wallet();
  });
}
refresh(); setInterval(refresh, 10000);
