import {sha256} from '@noble/hashes/sha2.js';
export const base64=(b:Uint8Array)=>btoa(String.fromCharCode(...b));
export async function fingerprint(file:Blob){const h=sha256.create();for(let p=0;p<file.size;p+=8*1024*1024)h.update(new Uint8Array(await file.slice(p,p+8*1024*1024).arrayBuffer()));return base64(h.digest());}
export type Session={id:string;part_size:number;size:number;state:string;parts?:{number:number;checksum:string;size:number}[]};
export type Api=(path:string,body?:unknown,key?:string)=>Promise<any>;
export async function upload(file:File,s:Session,api:Api,signal:AbortSignal,progress:(n:number)=>void){
 async function finish(){let result=await api(`/uploads/${s.id}/complete`,{});
 for(let attempt=0;result?.state==='COMPLETING'&&attempt<3600;attempt++){signal.throwIfAborted();await new Promise(r=>setTimeout(r,1000));result=await api(`/uploads/${s.id}`);}
 if(result?.state && result.state!=='COMPLETED')throw Error(`Completion ${result.state}; recover using saved session`);return result;}
 if(s.state==='COMPLETING'||s.state==='COMPLETED')return finish();
 const count=Math.max(1,Math.ceil(file.size/s.part_size));let next=0,done=0;
 const known=new Map((s.parts||[]).map(p=>[p.number,p]));
 async function worker(){while(next<count){signal.throwIfAborted();const i=next++;const blob=file.slice(i*s.part_size,Math.min(file.size,(i+1)*s.part_size));const checksum=base64(sha256(new Uint8Array(await blob.arrayBuffer())));const old=known.get(i+1);
 if(old?.checksum===checksum&&old.size===blob.size){done+=blob.size;progress(done);continue;}
 let success=false;for(let attempt=0;attempt<4;attempt++){signal.throwIfAborted();try{const signed=await api(`/uploads/${s.id}/parts`,{number:i+1,sha256:checksum});const headers=new Headers();for(const [k,v]of Object.entries(signed.headers as Record<string,string[]>))if(k.toLowerCase()!=='host')headers.set(k,v.join(','));const response=await fetch(signed.url,{method:'PUT',headers,body:blob,signal});if(!response.ok)throw Error(`Storage HTTP ${response.status}`);success=true;break;}catch(e){if(signal.aborted||attempt===3)throw e;await new Promise(r=>setTimeout(r,Math.random()*500+500*2**attempt));}}
 if(success){done+=blob.size;progress(done);}}}
 await Promise.all(Array.from({length:Math.min(3,count)},worker));signal.throwIfAborted();return finish();
}
