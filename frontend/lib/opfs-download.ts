import {checkpoint} from './download';
export function startOpfsDownload(id:string,authorization:string,onMessage:(message:any)=>void){
 const worker=new Worker(new URL('./opfs-download.worker.ts',import.meta.url),{type:'module'});
 worker.onmessage=event=>{onMessage(event.data);if(['done','paused','error'].includes(event.data.type))worker.terminate()};
 worker.onerror=event=>{onMessage({type:'error',message:event.message});worker.terminate()};
 worker.postMessage({type:'start',id,authorization});return {pause:()=>worker.postMessage({type:'pause'}),terminate:()=>worker.terminate()};
}
export async function prepareOpfsDownload(id:string,details:{version:string;sha256:string;size:number}){
 const saved=await checkpoint(undefined,id);if(saved)return saved;
 const root=await navigator.storage.getDirectory();const handle=await root.getFileHandle('harbor-'+id+'.download',{create:true});
 const value={id,version:details.version,sha256:details.sha256,size:Number(details.size),offset:0,handle};await checkpoint(value);return value;
}
