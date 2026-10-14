// Rutu BBQ Point of Sale: all calculations shown locally are previews; the server
// validates the menu, recalculates totals and stores orders under the same order ID sequence.
const posMenu=[
 {name:'BBQ Regular',price:10.00,desc:'1 bout · 2 stokjes saté · salade',photo:'menu_bbq_regular.webp'},
 {name:'BBQ Extra',price:15.00,desc:'2 bouten · 2 stokjes saté · salade',photo:'menu_bbq_extra.webp'},
 {name:'BBQ Family',price:25.00,desc:'3 bouten · 6 stokjes saté · 3 salades',photo:'menu_bbq_family.webp'},
 {name:'Extra bout',price:6.00,desc:'Los bij te bestellen',photo:'menu_extra_bout.webp'},
 {name:'Extra salade',price:2.50,desc:'Los bij te bestellen',photo:'menu_extra_salade.webp'},
 {name:'Portie saté',price:7.00,desc:'Los bij te bestellen',photo:'menu_portie_sate.webp'}
];
const posState={quantities:{},requestId:null,submitting:false};
const posImages=document.querySelector('header .brand-logo').getAttribute('src').replace(/rutu-logo\.png(?:\?.*)?$/,'menu/');
function posTotalCents(){return posMenu.reduce((sum,p)=>sum+Math.round(p.price*100)*(posState.quantities[p.name]||0),0)}
function posDraw(){
 const wrap=document.getElementById('posProducts'),cart=document.getElementById('posCart');
 if(!wrap||!cart)return;
 wrap.innerHTML=posMenu.map((p,i)=>{
  const visual=p.photo?'<img src="'+posImages+p.photo+'" alt="'+esc(p.name)+'" loading="lazy" decoding="async">':'<div class="pos-image-fallback" role="img" aria-label="'+esc(p.name)+'">🔥</div>';
  return '<article class="pos-product">'+visual+'<div class="pos-product-name">'+esc(p.name)+'</div>'+
   '<div class="pos-product-desc">'+esc(p.desc)+'</div><div class="pos-product-price">'+money(p.price)+'</div>'+
   '<button type="button" class="gold pos-add" onclick="posAdd('+i+')">+ Toevoegen</button></article>';
 }).join('');
 posDrawCart();
}
function posDrawCart(){
 const lines=posMenu.filter(p=>(posState.quantities[p.name]||0)>0);
 document.getElementById('posCart').innerHTML=lines.length?lines.map(p=>{
  const i=posMenu.indexOf(p),q=posState.quantities[p.name];
  return '<div class="pos-cart-line"><div><strong>'+esc(p.name)+'</strong><small>'+money(p.price*q)+'</small></div>'+
   '<div class="pos-qty-controls"><button type="button" class="gray" aria-label="Minder '+esc(p.name)+'" onclick="posChange('+i+',-1)">−</button>'+
   '<span>'+q+'</span><button type="button" class="gray" aria-label="Meer '+esc(p.name)+'" onclick="posChange('+i+',1)">+</button></div></div>';
 }).join(''):'<div class="hint">Nog geen gerechten geselecteerd.</div>';
 document.getElementById('posTotal').textContent=money(posTotalCents()/100);
 document.getElementById('posSubmit').disabled=posState.submitting||!lines.length;
}
function posAdd(index){posChange(index,1)}
function posChange(index,change){
 if(posState.submitting)return;
 const p=posMenu[index];if(!p)return;
 const next=Math.min(25,Math.max(0,(posState.quantities[p.name]||0)+change));
 if(next)posState.quantities[p.name]=next;else delete posState.quantities[p.name];
 posState.requestId=null;posDrawCart();
}
function posClear(){
 if(posState.submitting)return;
 posState.quantities={};posState.requestId=null;
 document.getElementById('posMessage').textContent='';
 posDrawCart();
}
function posId(){
 const random=typeof crypto!=='undefined'&&crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random().toString(36).slice(2)+Math.random().toString(36).slice(2);
 return 'cashier-'+random;
}
async function posSave(){
 if(posState.submitting||!posTotalCents())return;
 const pay=document.getElementById('posPayment').value;
 const customer=document.getElementById('posCustomer').value.trim().slice(0,80)||'Balie';
 const total=money(posTotalCents()/100);
 if(!confirm('Bestelling voor '+customer+' toevoegen?\nTotaal: '+total+'\nBetaalkeuze: '+document.getElementById('posPayment').selectedOptions[0].textContent))return;
 if(!posState.requestId)posState.requestId=posId();
 const requestId=posState.requestId;
 const message=document.getElementById('posMessage'),submit=document.getElementById('posSubmit');
 posState.submitting=true;submit.disabled=true;message.className='pos-status hint';message.textContent='Bestelling wordt opgeslagen…';
 try{
  const response=await api('business_create_order','POST',{
    request_id:requestId,customer,payment:pay,items:Object.assign({},posState.quantities)
  });
  // Success, including a replay after a lost response. Never submit the same order twice.
  posState.quantities={};posState.requestId=null;
  document.getElementById('posCustomer').value='';
  document.getElementById('posPayment').value='later';
  message.className='pos-status ok';
  message.textContent='Bestelling #'+response.order.id+' opgeslagen ✓'+(response.existing?' (al eerder verwerkt)':'');
  await sync();
 }catch(err){
  // Keep cart and idempotency key; retrying cannot silently create a second order.
  message.className='pos-status error';message.textContent='Niet bevestigd: '+err.message+' — probeer opnieuw (zonder dubbele bestelling).';
 }finally{posState.submitting=false;posDrawCart()}
}
posDraw();
