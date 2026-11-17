import {download,checkpoint} from './download';
const scope:any=globalThis;let control:AbortController|undefined;
scope.onmessage=async(event:MessageEvent)=>{
 if(event.data.type==='pause'){control?.abort();return;}
 if(event.data.type!=='start'||control)return;
 control=new AbortController();let access:any;
 try{
  const {id,authorization}=event.data;const saved=await checkpoint(undefined,id);if(!saved)throw Error('Download checkpoint missing');
  access=await saved.handle.createSyncAccessHandle();
  if(access.getSize()<saved.offset)throw Error('Destination was truncated');access.truncate(saved.offset);access.flush();
  let committed=saved.offset,position=committed;
  const destination={getFile:async()=>({size:access.getSize(),stream:()=>{let cursor=0;return new ReadableStream<Uint8Array>({async pull(controller){const remaining=access.getSize()-cursor;if(!remaining){controller.close();return;}const buffer=new Uint8Array(Math.min(1024*1024,remaining));const n=access.read(buffer,{at:cursor});if(!n)throw Error('Disk read stalled');cursor+=n;controller.enqueue(buffer.subarray(0,n));if(cursor%(64*1024*1024)===0)await new Promise(r=>setTimeout(r,0));}})}}),
   createWritable:async()=>({seek:async(n:number)=>{position=n},write:async(bytes:Uint8Array)=>{let offset=0;while(offset<bytes.length){const n=access.write(bytes.subarray(offset),{at:position});if(!n)throw Error('Disk write stalled');position+=n;offset+=n}},truncate:async(n:number)=>access.truncate(n),close:async()=>access.flush(),abort:async()=>{access.truncate(committed);access.flush()}})};
  await download({...saved,handle:destination},async path=>{const r=await fetch('/api/v1'+path,{headers:{Authorization:authorization}});if(!r.ok)throw Error(`Authorization HTTP ${r.status}`);return r.json()},control.signal,(bytes,state)=>scope.postMessage({type:'progress',bytes,state}),async value=>{await checkpoint({...value,handle:saved.handle});committed=value.offset;});
  scope.postMessage({type:'done',bytes:access.getSize(),verified:true});
 }catch(error){scope.postMessage({type:control.signal.aborted?'paused':'error',message:String(error)});}
 finally{access?.close();control=undefined;}
};
