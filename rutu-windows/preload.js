const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('rutu', {
  openCustomer: () => ipcRenderer.send('open-customer'),
  openBusiness: () => ipcRenderer.send('open-business'),
  getServerInfo: () => ipcRenderer.invoke('get-server-info'),
  getOrders: () => ipcRenderer.invoke('get-orders'),
  syncOrders: () => ipcRenderer.invoke('sync-cloud-orders'),
  getAnnouncement: () => ipcRenderer.invoke('get-business-announcement'),
  setAnnouncement: value => ipcRenderer.invoke('set-business-announcement', value),
  placeOrder: order => ipcRenderer.invoke('place-order', order),
  setStatus: (id, status) => ipcRenderer.invoke('set-status', { id, status }),
  resetOrders: () => ipcRenderer.invoke('reset-orders'),
  onOrdersUpdated: callback => ipcRenderer.on('orders-updated', (_event, orders) => callback(orders))
});
