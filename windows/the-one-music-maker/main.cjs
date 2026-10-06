'use strict';
const {app,BrowserWindow,session,dialog}=require('electron');const path=require('node:path');
app.setName('The One Studio');
if(!app.requestSingleInstanceLock())app.quit();
else{let win;app.on('second-instance',()=>{if(win){if(win.isMinimized())win.restore();win.focus();}});
app.whenReady().then(()=>{
 session.defaultSession.setPermissionCheckHandler((_wc,permission)=>permission==='media'||permission==='midi'||permission==='midiSysex');
 session.defaultSession.setPermissionRequestHandler(async(wc,permission,callback,details)=>{
  if((permission==='midi'||permission==='midiSysex')&&wc===win?.webContents)return callback(true);
  if(permission!=='media'||wc!==win?.webContents||(details.mediaTypes||[]).some(t=>t!=='audio'))return callback(false);
  const result=await dialog.showMessageBox(win,{type:'question',buttons:['Toestaan','Weigeren'],defaultId:1,cancelId:1,title:'Microfoon',message:'Mag The One Studio jouw microfoon gebruiken voor deze opname?'});callback(result.response===0);
 });
 win=new BrowserWindow({width:1400,height:900,minWidth:860,minHeight:620,backgroundColor:'#08121b',title:'The One Studio',autoHideMenuBar:true,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true}});
 win.webContents.setWindowOpenHandler(()=>({action:'deny'}));win.webContents.on('will-navigate',e=>e.preventDefault());
 win.webContents.on('will-prevent-unload',event=>{const result=dialog.showMessageBoxSync(win,{type:'warning',buttons:['Blijven','Afsluiten zonder opslaan'],defaultId:0,cancelId:0,message:'Je hebt niet-opgeslagen wijzigingen of een actieve opname.'});if(result===1)event.preventDefault();});
 win.loadFile(path.join(__dirname,'studio','index.html'));
 });app.on('window-all-closed',()=>app.quit());}
