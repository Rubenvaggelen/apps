const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

let orders = [];
let nextId = 1046;
let customerWindow = null;
let businessWindow = null;

function makeWindow(file, opts = {}) {
  const win = new BrowserWindow({
    width: opts.width || 520,
    height: opts.height || 850,
    minWidth: 420,
    minHeight: 650,
    backgroundColor: '#0b0b0d',
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
  businessWindow = makeWindow('business.html', { title: 'Rutu BBQ — Bedrijf' });
  businessWindow.on('closed', () => businessWindow = null);
}

app.whenReady().then(() => {
  const launcher = makeWindow('launcher.html', { width: 680, height: 520, title: 'Rutu BBQ Simulator' });
  ipcMain.on('open-customer', openCustomer);
  ipcMain.on('open-business', openBusiness);

  ipcMain.handle('get-orders', () => JSON.parse(JSON.stringify(orders)));
  ipcMain.handle('place-order', (_event, order) => {
    const created = {
      id: nextId++,
      items: Array.isArray(order.items) ? order.items : [],
      total: Number(order.total || 0),
      customer: order.customer || 'Testklant',
      created: new Date().toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }),
      status: 'Nieuw'
    };
    orders.unshift(created);
    broadcast();
    return created;
  });

  ipcMain.handle('set-status', (_event, { id, status }) => {
    const item = orders.find(o => o.id === id);
    if (item) item.status = status;
    broadcast();
    return item || null;
  });

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

app.on('window-all-closed', () => app.quit());
