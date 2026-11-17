const {chromium}=require('playwright');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const project=path.resolve(__dirname,'../..'),profile=fs.mkdtempSync(path.join(os.tmpdir(),'harbor-browser-profile-')),file=path.join(profile,'restart-'+crypto.randomUUID()+'.bin');
const auth=process.env.DRIVE_AUTH||'Basic '+Buffer.from('alice:harbor-local-benchmark-password').toString('base64');
const origin=process.env.BROWSER_ORIGIN||'http://localhost:3012',api=process.env.DRIVE_API||'http://localhost:28080/api/v1',storage=process.env.STORAGE_ORIGIN||'http://localhost:29000';
async function call(url){const r=await fetch(api+url,{headers:{Authorization:auth}});assert.equal(r.status,200);return r.json()}
async function context(){return chromium.launchPersistentContext(profile,{headless:true,acceptDownloads:true})}
async function connect(page){await page.goto(origin);await page.getByLabel('Authorization header').fill(auth);await page.getByRole('button',{name:'Connect workspace'}).click()}
async function waitFor(fn,ms=120000){const end=Date.now()+ms;while(Date.now()<end){const value=await fn();if(value)return value;await new Promise(r=>setTimeout(r,100))}throw Error('Condition timed out')}
(async()=>{
 const preflight=await fetch(storage+'/drive/cors-probe',{method:'OPTIONS',headers:{Origin:origin,'Access-Control-Request-Method':'PUT','Access-Control-Request-Headers':'content-type,x-amz-checksum-sha256'}});const allowed=preflight.headers.get('access-control-allow-origin');assert(preflight.ok&&(allowed==='*'||allowed===origin),'Storage CORS must allow the selected BROWSER_ORIGIN before running this test');
 console.log('Preparing fixture');const report={fixture_bytes:40*1024*1024};const source=Buffer.alloc(report.fixture_bytes);for(let i=0;i<source.length;i+=32)crypto.createHash('sha256').update(String(i)).digest().copy(source,i);fs.writeFileSync(file,source);const expected=crypto.createHash('sha256').update(source).digest('base64');
 let browser=await context(),page=await browser.newPage();let puts=0;
 await page.route(storage+'/**',route=>{if(route.request().method()==='PUT'&&++puts>2)return route.abort();return route.continue()});
 console.log('Opening first browser');await connect(page);console.log('Selecting fixture');await page.locator('input[type=file]').setInputFiles(file);
 const id=await waitFor(()=>page.evaluate(()=>localStorage.getItem('harbor-session')));
 const before=await waitFor(async()=>{const s=await call('/uploads/'+id);return s.parts?.length>=1?s:null});
 console.log('Provider parts persisted');report.parts_before_restart=before.parts.length;await browser.close();
 console.log('Restarting browser');browser=await context();page=await browser.newPage();await connect(page);assert.equal(await page.evaluate(()=>localStorage.getItem('harbor-session')),id);
 await page.locator('input[type=file]').setInputFiles(file);const complete=await waitFor(async()=>{const messages=await page.getByRole('status').allTextContents();const failure=messages.find(x=>/^(Error|TypeError|AbortError):/.test(x));if(failure)throw Error(failure);const s=await call('/uploads/'+id);if(s.state==='QUARANTINED')throw Error('Fixture quarantined');return s.state==='COMPLETED'?s:null});
 console.log('Upload resumed and completed');const credentials=await call('/files/'+complete.node+'/download');assert.equal(credentials.sha256,expected);report.upload_restart='passed';report.file_id=complete.node;
 // Real OPFS FileSystemFileHandle is structured-cloned into IndexedDB; no mocked filesystem.
 await page.goto(origin+'/recovery-lab');await page.waitForFunction(()=>!!window.__harborRecovery);
 await page.evaluate(async({auth,id})=>{const h=window.__harborRecovery;const handle=await (await navigator.storage.getDirectory()).getFileHandle('download.bin',{create:true});const response=await fetch('/api/v1/files/'+id+'/download',{headers:{Authorization:auth}});const d=await response.json();await h.checkpoint({id,version:d.version,size:Number(d.size),sha256:d.sha256,offset:0,handle});window.downloadStop=new AbortController();window.downloadRun=h.download(await h.checkpoint(undefined,id),async p=>{const r=await fetch('/api/v1'+p,{headers:{Authorization:auth}});if(!r.ok)throw Error('Authorization failed');return r.json()},window.downloadStop.signal,(n,state)=>{window.downloadProgress={n,state};if(state==='Downloading')window.downloadStop.abort()}).catch(e=>window.downloadError=String(e));},{auth,id:complete.node});
 console.log('Starting disk-backed parallel download');const partial=await waitFor(()=>page.evaluate(()=>window.downloadProgress?.state==='Downloading'?window.downloadProgress:null));assert.equal(partial.n,24*1024*1024);report.download_checkpoint_bytes=partial.n;
 await browser.close();browser=await context();page=await browser.newPage();await page.goto(origin+'/recovery-lab');await page.waitForFunction(()=>!!window.__harborRecovery);
 const result=await page.evaluate(async({auth,id})=>{const h=window.__harborRecovery,c=await h.checkpoint(undefined,id);if(!c)throw Error('Checkpoint lost');let state;await h.download(c,async p=>{const r=await fetch('/api/v1'+p,{headers:{Authorization:auth}});if(!r.ok)throw Error('Authorization failed');return r.json()},new AbortController().signal,(_,s)=>state=s);const blob=await c.handle.getFile();return {state,size:blob.size,hash:await h.fingerprint(blob)}},{auth,id:complete.node});
 assert.equal(result.state,'Verified');assert.equal(result.hash,expected);assert.equal(result.size,source.length);report.download_restart='passed';report.destination='real Chromium OPFS handle persisted in IndexedDB';report.verified_sha256=result.hash;
 await browser.close();fs.writeFileSync(process.env.RESTART_REPORT||path.join(project,'test-results/browser-restart.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exit(1)});
