import {sha256} from '@noble/hashes/sha2.js';
import {base64,type Api} from './transfer.ts';
export type Checkpoint={id:string;version:string;sha256:string;size:number;offset:number;etag?:string;handle:any};
async function database(){return new Promise<IDBDatabase>((resolve,reject)=>{const r=indexedDB.open('harbor-downloads',1);r.onupgradeneeded=()=>r.result.createObjectStore('downloads',{keyPath:'id'});r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});}
export async function checkpoint(value?:Checkpoint,id?:string):Promise<Checkpoint|undefined>{const db=await database();try{return await new Promise((resolve,reject)=>{const t=db.transaction('downloads',value?'readwrite':'readonly');const r=value?t.objectStore('downloads').put(value):t.objectStore('downloads').get(id!);let found:any;r.onsuccess=()=>found=value||r.result;t.oncomplete=()=>resolve(found);t.onerror=()=>reject(t.error);});}finally{db.close();}}
export function validateRange(response:Response,start:number,end:number,total:number,etag?:string){
 if(response.status!==206||response.headers.get('Content-Range')!==`bytes ${start}-${end}/${total}`)throw Error('Storage returned an invalid byte range');
 const next=response.headers.get('ETag');if(!next||next.startsWith('W/')||(etag&&etag!==next))throw Error('Object validator changed or is unavailable');return next;
}
/** Three parallel ranges, at most 24 MiB of byte buffers. Ordered atomic batch commit then durable checkpoint. */
export async function download(c:Checkpoint,api:Api,signal:AbortSignal,progress:(n:number,state:string)=>void,persist:(c:Checkpoint)=>Promise<unknown>=checkpoint){
 if(c.handle.requestPermission && await c.handle.requestPermission({mode:'readwrite'})!=='granted')throw Error('Destination permission required');
 const existing=await c.handle.getFile();if(existing.size<c.offset)throw Error('Partial destination was truncated; restart download');
 while(c.offset<c.size){
 signal.throwIfAborted();const credentials=await api(`/files/${c.id}/download?version=${c.version}`);
 if(credentials.version!==c.version||credentials.sha256!==c.sha256||Number(credentials.size)!==c.size)throw Error('Source version changed');
 const stop=new AbortController(),combined=AbortSignal.any([signal,stop.signal]);
 const ranges=Array.from({length:Math.min(3,Math.ceil((c.size-c.offset)/(8*1024*1024)))},(_,i)=>{const start=c.offset+i*8*1024*1024;return {start,end:Math.min(c.size,start+8*1024*1024)-1};});
 const tasks=ranges.map(async range=>{
  const response=await fetch(credentials.url,{headers:{Range:`bytes=${range.start}-${range.end}`,...(c.etag?{'If-Range':c.etag}:{})},signal:combined});
  let etag:string;try{etag=validateRange(response,range.start,range.end,c.size,c.etag);}catch(e){await response.body?.cancel().catch(()=>{});throw e;}
  if(!response.body)throw Error('Missing range body');
  const bytes=new Uint8Array(range.end-range.start+1),reader=response.body.getReader();let used=0;
  try{for(;;){combined.throwIfAborted();const {done,value}=await reader.read();if(done)break;if(used+value.length>bytes.length)throw Error('Oversized range response');bytes.set(value,used);used+=value.length;}if(used!==bytes.length)throw Error('Truncated range response');}finally{await reader.cancel().catch(()=>{});}
  return {...range,bytes,etag};
 });
 let completed:Awaited<typeof tasks[number]>[];
 try{completed=await Promise.all(tasks);}catch(e){stop.abort();await Promise.allSettled(tasks);throw e;}
 const etag=completed[0].etag;if(completed.some(part=>part.etag!==etag))throw Error('Parallel ranges refer to different validators');
 signal.throwIfAborted();const writer=await c.handle.createWritable({keepExistingData:true});
 try{for(const part of completed){signal.throwIfAborted();await writer.seek(part.start);await writer.write(part.bytes);}await writer.truncate(completed.at(-1)!.end+1);await writer.close();}
 catch(e){await writer.abort().catch(()=>{});throw e;}
 c={...c,offset:completed.at(-1)!.end+1,etag};await persist(c);progress(c.offset,'Downloading');
 }
 progress(c.offset,'Verifying disk contents');const hash=sha256.create();const file=await c.handle.getFile();if(file.size!==c.size)throw Error('Destination size changed');
 const reader=file.stream().getReader();try{for(;;){signal.throwIfAborted();const {done,value}=await reader.read();if(done)break;hash.update(value);}}finally{await reader.cancel().catch(()=>{});}
 if(base64(hash.digest())!==c.sha256)throw Error('Download SHA-256 mismatch; restart using a clean destination');progress(c.size,'Verified');
}
export async function forgetCheckpoint(id:string){const db=await database();try{await new Promise<void>((resolve,reject)=>{const t=db.transaction('downloads','readwrite');t.objectStore('downloads').delete(id);t.oncomplete=()=>resolve();t.onerror=()=>reject(t.error)});}finally{db.close();}}
