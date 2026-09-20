const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const http = require('http');
const os = require('os');
const fs = require('fs');

let orders = [];
let nextId = 1046;
let customerWindow = null;
let businessWindow = null;
let apiServer = null;
const API_PORT = 8765;
const DEFAULT_CLOUD_API = 'https://rubenvanaggelen.com/rutu-api/index.php';
let cloudConfig = null;
let cloudOnline = false;
let cloudTimer = null;

function makeWindow(file, opts = {}) {
  const win = new BrowserWindow({
    width: opts.width || 520,
    height: opts.height || 850,
    minWidth: 420,
    minHeight: 650,
    backgroundColor: '#090807',
    icon: path.join(__dirname, 'assets', 'rutu.ico'),
    title: opts.title || 'Rutu BBQ Simulator',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  win.loadFile(file);
  return win;
}

function broadcast() {
  const payload = JSON.parse(JSON.stringify(orders));
  for (const win of [customerWindow, businessWindow]) {
    if (win && !win.isDestroyed()) win.webContents.send('orders-updated', payload);
  }
}

function openCustomer() {
  if (customerWindow && !customerWindow.isDestroyed()) return customerWindow.focus();
  customerWindow = makeWindow('customer.html', { title: 'Rutu BBQ — Klant' });
  customerWindow.on('closed', () => customerWindow = null);
}

function openBusiness() {
  if (businessWindow && !businessWindow.isDestroyed()) return businessWindow.focus();
  businessWindow = makeWindow('business.html', { width: 1120, height: 850, title: 'Rutu BBQ — Bedrijf' });
  businessWindow.on('closed', () => businessWindow = null);
}

function localAddresses() {
  const result = [];
  for (const values of Object.values(os.networkInterfaces())) {
    for (const item of values || []) {
      if (item.family === 'IPv4' && !item.internal) result.push(item.address);
    }
  }
  return [...new Set(result)];
}

function businessConfigPath() { return path.join(os.homedir(), 'Documents', 'Rutu BBQ', 'rutu-business.json'); }

function loadCloudConfig() {
  try {
    const raw = JSON.parse(fs.readFileSync(businessConfigPath(), 'utf8'));
    if (raw && raw.businessKey) { cloudConfig = { apiBase: String(raw.apiBase || DEFAULT_CLOUD_API), businessKey: String(raw.businessKey) }; return; }
  } catch (_) {}
  cloudConfig = null;
}

async function cloudRequest(action, method = 'GET', body = null) {
  if (!cloudConfig) throw new Error('Rutu business config ontbreekt');
  const url = new URL(cloudConfig.apiBase);
  url.searchParams.set('action', action);
  const headers = { 'Accept': 'application/json', 'X-Rutu-Business-Key': cloudConfig.businessKey };
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
  const response = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await response.text(); let parsed = {};
  try { parsed = text ? JSON.parse(text) : {}; } catch (_) {}
  if (!response.ok || parsed.ok === false) throw new Error(parsed.error || ('HTTP ' + response.status));
  return parsed;
}

async function syncCloudOrders() {
  if (!cloudConfig) return false;
  try {
    const result = await cloudRequest('business_orders');
    const remote = Array.isArray(result.orders) ? result.orders : [];
    orders = remote.map(o => ({ id:Number(o.id), items:normalizeItems(o.items), total:Number(o.total||0), customer:String(o.customer||'Online klant'), created:String(o.created_display||o.created||''), status:String(o.status||'Nieuw') }));
    nextId = Math.max(1046, ...orders.map(o => o.id + 1));
    cloudOnline = true; broadcast(); return true;
  } catch (_) { cloudOnline = false; return false; }
}

async function setCloudOrderStatus(id, status) {
  const result = await cloudRequest('business_status', 'POST', { id:Number(id), status:String(status) });
  const updated = result.order || null;
  if (updated) { const local = orders.find(o => o.id === Number(updated.id)); if (local) local.status = String(updated.status || local.status); }
  cloudOnline = true; broadcast(); return updated;
}
function normalizeItems(items) {
  if (Array.isArray(items)) return items.map(i => ({ name: String(i.name || ''), qty: Number(i.qty || 0) })).filter(i => i.name && i.qty > 0);
  if (items && typeof items === 'object') return Object.entries(items).map(([name, qty]) => ({ name, qty: Number(qty || 0) })).filter(i => i.qty > 0);
  return [];
}

function addOrder(input = {}) {
  let requestedId = Number(input.id || 0);
  if (!Number.isFinite(requestedId) || requestedId <= 0 || orders.some(o => o.id === requestedId)) requestedId = nextId++;
  nextId = Math.max(nextId, requestedId + 1);
  const created = {
    id: requestedId,
    items: normalizeItems(input.items),
    total: Number(input.total || 0),
    customer: input.customer || 'Android klant',
    created: new Date().toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }),
    status: input.status || 'Nieuw'
  };
  orders.unshift(created);
  broadcast();
  return created;
}

function setOrderStatus(id, status) {
  const item = orders.find(o => o.id === Number(id));
  if (item) item.status = String(status || item.status);
  broadcast();
  return item || null;
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => {
      raw += chunk;
      if (raw.length > 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      try { resolve(raw ? JSON.parse(raw) : {}); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function startApiServer() {
  if (apiServer) return;
  apiServer = http.createServer(async (req, res) => {
    if (req.method === 'OPTIONS') return sendJson(res, 200, { ok: true });
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    try {
      if (req.method === 'GET' && url.pathname === '/api/health') {
        return sendJson(res, 200, { ok: true, name: 'Rutu BBQ Windows Bedrijf', port: API_PORT, addresses: localAddresses() });
      }
      if (req.method === 'GET' && url.pathname === '/api/orders') {
        return sendJson(res, 200, orders);
      }
      if (req.method === 'POST' && url.pathname === '/api/orders') {
        const body = await readJson(req);
        return sendJson(res, 201, addOrder(body));
      }
      const statusMatch = url.pathname.match(/^\/api\/orders\/(\d+)\/status$/);
      if (req.method === 'POST' && statusMatch) {
        const body = await readJson(req);
        const updated = setOrderStatus(Number(statusMatch[1]), body.status);
        return sendJson(res, updated ? 200 : 404, updated || { error: 'Order not found' });
      }
      return sendJson(res, 404, { error: 'Not found' });
    } catch (error) {
      return sendJson(res, 400, { error: String(error.message || error) });
    }
  });
  apiServer.listen(API_PORT, '0.0.0.0');
}

app.whenReady().then(() => {
  startApiServer();
  const launcher = makeWindow('launcher.html', { width: 980, height: 720, title: 'Rutu BBQ Simulator' });
  ipcMain.on('open-customer', openCustomer);
  ipcMain.on('open-business', openBusiness);

  ipcMain.handle('get-server-info', () => ({ port: API_PORT, addresses: localAddresses(), online: cloudOnline, cloudConfigured: Boolean(cloudConfig), cloudApi: cloudConfig ? cloudConfig.apiBase : DEFAULT_CLOUD_API }));
  ipcMain.handle('get-orders', () => JSON.parse(JSON.stringify(orders)));
  ipcMain.handle('place-order', (_event, order) => addOrder(order));
  ipcMain.handle('set-status', async (_event, { id, status }) => setOrderStatus(id, status));
  ipcMain.handle('reset-orders', () => {
    orders = [];
    nextId = 1046;
    broadcast();
    return true;
  });

  launcher.on('closed', () => {
    if (customerWindow && !customerWindow.isDestroyed()) customerWindow.close();
    if (businessWindow && !businessWindow.isDestroyed()) businessWindow.close();
  });
});

app.on('before-quit', () => {
  if (apiServer) apiServer.close();
});
app.on('window-all-closed', () => app.quit());

