const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('rutu', {
  openCustomer: () => ipcRenderer.send('open-customer'),
  openBusiness: () => ipcRenderer.send('open-business'),
  getServerInfo: () => ipcRenderer.invoke('get-server-info'),
  getOrders: () => ipcRenderer.invoke('get-orders'),
  placeOrder: order => ipcRenderer.invoke('place-order', order),
  setStatus: (id, status) => ipcRenderer.invoke('set-status', { id, status }),
  resetOrders: () => ipcRenderer.invoke('reset-orders'),
  onOrdersUpdated: callback => ipcRenderer.on('orders-updated', (_event, orders) => callback(orders))
});
