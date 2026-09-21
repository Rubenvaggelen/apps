const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const http = require('http');
const os = require('os');
const fs = require('fs');
const crypto = require('crypto');
const { spawn } = require('child_process');

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
const WINDOWS_UPDATE_META = 'https://rubenvanaggelen.com/rutu-updates/windows.json';
let updateCheckRunning = false;

function compareVersions(a, b) {
  const pa = String(a || '').split('.').map(x => Number(x) || 0);
  const pb = String(b || '').split('.').map(x => Number(x) || 0);
  const size = Math.max(pa.length, pb.length);
  for (let i = 0; i < size; i++) {
    const av = pa[i] || 0, bv = pb[i] || 0;
    if (av > bv) return 1;
    if (av < bv) return -1;
  }
  return 0;
}

async function checkForWindowsUpdate(parentWindow) {
  if (updateCheckRunning) return;
  updateCheckRunning = true;
  try {
    const response = await fetch(WINDOWS_UPDATE_META, { headers: { 'Cache-Control': 'no-cache' } });
    if (!response.ok) return;
    const meta = await response.json();
    if (!meta || !meta.version || !meta.url) return;
    if (compareVersions(meta.version, app.getVersion()) <= 0) return;

    const choice = await dialog.showMessageBox(parentWindow, {
      type: 'info',
      title: 'Rutu update beschikbaar',
      message: 'Er staat een nieuwe versie van Rutu BBQ klaar.',
      detail: 'Klik op Bijwerken. De update wordt automatisch gedownload en geïnstalleerd.',
      buttons: ['Bijwerken', 'Later'],
      defaultId: 0,
      cancelId: 1,
      noLink: true
    });
    if (choice.response !== 0) return;

    const download = await fetch(meta.url, { headers: { 'Cache-Control': 'no-cache' } });
    if (!download.ok) throw new Error('Download mislukt');
    const bytes = Buffer.from(await download.arrayBuffer());
    if (meta.sha256) {
      const actual = crypto.createHash('sha256').update(bytes).digest('hex');
      if (actual.toLowerCase() !== String(meta.sha256).toLowerCase()) throw new Error('Checksum ongeldig');
    }

    const installer = path.join(app.getPath('temp'), 'Rutu-BBQ-Update.exe');
    fs.writeFileSync(installer, bytes);
    spawn(installer, ['/S'], { detached: true, stdio: 'ignore' }).unref();
    setTimeout(() => app.quit(), 700);
  } catch (error) {
    try {
      await dialog.showMessageBox(parentWindow, {
        type: 'warning',
        title: 'Update niet gelukt',
        message: 'Rutu kon de update nu niet installeren.',
        detail: 'De huidige versie blijft gewoon werken. Rutu probeert het later opnieuw.',
        buttons: ['OK']
      });
    } catch (_) {}
  } finally {
    updateCheckRunning = false;
  }
}

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

async function openBusiness() {
  loadCloudConfig();
  if (cloudConfig) await syncCloudOrders();
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

async function saveBusinessKey(value) {
  const valueText = String(value || '').trim();
  if (!valueText) throw new Error('Vul de bedrijfskey in.');
  const previous = cloudConfig;
  cloudConfig = { apiBase: DEFAULT_CLOUD_API, businessKey: valueText };
  try {
    await cloudRequest('business_orders');
    const file = businessConfigPath();
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(cloudConfig, null, 2), 'utf8');
    await syncCloudOrders();
    return { ok: true };
  } catch (error) {
    cloudConfig = previous;
    throw new Error('De bedrijfskey is niet geldig of de server is niet bereikbaar.');
  }
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
    orders = remote.map(o => ({ id:Number(o.id), items:normalizeItems(o.items), total:Number(o.total||0), customer:String(o.customer||'Online klant'), delivery:Boolean(o.delivery), address:String(o.address||''), postcode:String(o.postcode||''), deliveryFee:Number(o.delivery_fee||0), created:String(o.created||o.created_display||''), status:String(o.status||'Nieuw'), historyHidden:Boolean(o.history_hidden), historyCleared:Boolean(o.history_cleared) }));
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
    delivery: Boolean(input.delivery),
    address: String(input.address || ''),
    postcode: String(input.postcode || ''),
    deliveryFee: Number(input.deliveryFee || input.delivery_fee || 0),
    created: new Date().toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }),
    status: input.status || 'Nieuw'
  };
  orders.unshift(created);
  broadcast();
  return created;
}

async function setOrderStatus(id, status) {
  if (cloudConfig) {
    try { return await setCloudOrderStatus(id, status); }
    catch (_) { cloudOnline = false; return null; }
  }
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
        const updated = await setOrderStatus(Number(statusMatch[1]), body.status);
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
  loadCloudConfig();
  startApiServer();
  if (cloudConfig) {
    syncCloudOrders();
    cloudTimer = setInterval(() => {
      loadCloudConfig();
      if (cloudConfig) syncCloudOrders();
    }, 2500);
  }
  const launcher = makeWindow('launcher.html', { width: 980, height: 720, title: 'Rutu BBQ Simulator' });
  setTimeout(() => checkForWindowsUpdate(launcher), 1500);
  ipcMain.on('open-customer', openCustomer);
  ipcMain.on('open-business', openBusiness);

  ipcMain.handle('get-server-info', async () => {
    loadCloudConfig();
    if (cloudConfig) await syncCloudOrders();
    return { port: API_PORT, addresses: localAddresses(), online: cloudOnline, cloudConfigured: Boolean(cloudConfig), cloudApi: cloudConfig ? cloudConfig.apiBase : DEFAULT_CLOUD_API };
  });
  ipcMain.handle('save-business-key', async (_event, value) => saveBusinessKey(value));
  ipcMain.handle('get-orders', async () => {
    loadCloudConfig();
    if (cloudConfig) await syncCloudOrders();
    return JSON.parse(JSON.stringify(orders));
  });
  ipcMain.handle('sync-cloud-orders', async () => {
    loadCloudConfig();
    return cloudConfig ? syncCloudOrders() : false;
  });
  ipcMain.handle('get-business-announcement', async () => {
    loadCloudConfig();
    if (!cloudConfig) return { active:false, title:'', message:'', from:'', until:'' };
    const result = await cloudRequest('business_announcement');
    return result.announcement || {};
  });
  ipcMain.handle('set-business-announcement', async (_event, announcement) => {
    loadCloudConfig();
    if (!cloudConfig) throw new Error('Rutu business config ontbreekt');
    const result = await cloudRequest('business_announcement', 'POST', announcement || {});
    return result.announcement || {};
  });
  ipcMain.handle('hide-business-history-order', async (_event, id) => {
    loadCloudConfig();
    if (!cloudConfig) throw new Error('Rutu business config ontbreekt');
    await cloudRequest('business_hide_history', 'POST', { id:Number(id) });
    await syncCloudOrders();
    return true;
  });
  ipcMain.handle('clear-business-history', async () => {
    loadCloudConfig();
    if (!cloudConfig) throw new Error('Rutu business config ontbreekt');
    const result = await cloudRequest('business_clear_history', 'POST', {});
    await syncCloudOrders();
    return result;
  });
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
  if (cloudTimer) clearInterval(cloudTimer);
  if (apiServer) apiServer.close();
});
app.on('window-all-closed', () => app.quit());

