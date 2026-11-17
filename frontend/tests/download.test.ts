import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateRange,download} from '../lib/download.ts';
import {fingerprint} from '../lib/transfer.ts';
test('range contract rejects whole response, malformed intervals and changed validators',()=>{
 assert.throws(()=>validateRange(new Response('whole',{status:200}),0,2,3));
 assert.throws(()=>validateRange(new Response('abc',{status:206,headers:{'Content-Range':'bytes 1-3/4',ETag:'"x"'}}),0,2,3));
 assert.throws(()=>validateRange(new Response('abc',{status:206,headers:{'Content-Range':'bytes 0-2/3',ETag:'"y"'}}),0,2,3,'"x"'));
});
test('resume writes only missing bytes and verifies disk contents',async()=>{
 const original=globalThis.fetch;let disk=new Uint8Array([97]),position=0,checkpointed=0,verified=false;
 const handle={requestPermission:async()=> 'granted',getFile:async()=>new Blob([disk]),createWritable:async()=>({seek:async(n:number)=>position=n,write:async(value:Uint8Array)=>{const next=new Uint8Array(position+value.length);next.set(disk);next.set(value,position);position+=value.length;disk=next},truncate:async()=>{},close:async()=>{},abort:async()=>{}})};
 globalThis.fetch=async(_url,options)=>{assert.equal((options?.headers as any).Range,'bytes=1-2');return new Response('bc',{status:206,headers:{'Content-Range':'bytes 1-2/3',ETag:'"same"'}})};
 try{const hash=await fingerprint(new Blob(['abc']));await download({id:'f',version:'v',size:3,sha256:hash,offset:1,etag:'"same"',handle},async()=>({url:'https://storage.test',version:'v',size:3,sha256:hash}),new AbortController().signal,(_,state)=>verified=state==='Verified',async c=>{checkpointed=c.offset});assert.equal(checkpointed,3);assert.equal(verified,true);}finally{globalThis.fetch=original}
});
test('changed source identity is rejected before opening the destination writer',async()=>{
 let writes=0;const handle={requestPermission:async()=> 'granted',getFile:async()=>new Blob([]),createWritable:async()=>{writes++;throw Error('Must not write')}};
 await assert.rejects(()=>download({id:'f',version:'old',size:3,sha256:'hash',offset:0,handle},async()=>({version:'new',size:3,sha256:'hash'}),new AbortController().signal,()=>{},async()=>{}),/Source version changed/);assert.equal(writes,0);
});
test('parallel ranges finish out of order but commit in byte order with a three-request cap',async()=>{
 const original=globalThis.fetch,part=8*1024*1024,total=3*part,chunk=new Uint8Array(part).fill(37),seeks:number[]=[],committed:Uint8Array<ArrayBuffer>[]=[];let active=0,peak=0,closed=false;
 const hash=await fingerprint(new Blob([chunk,chunk,chunk]));
 const handle={requestPermission:async()=> 'granted',getFile:async()=>closed?new Blob(committed):new Blob([]),createWritable:async()=>({seek:async(n:number)=>{seeks.push(n)},write:async(data:Uint8Array<ArrayBuffer>)=>{committed.push(data)},truncate:async()=>{},close:async()=>{closed=true},abort:async()=>{}})};
 globalThis.fetch=async(_url,options)=>{active++;peak=Math.max(peak,active);const range=(options?.headers as any).Range;const start=Number(range.match(/bytes=(\d+)/)[1]);await new Promise(r=>setTimeout(r,30-start/part*10));active--;return new Response(chunk,{status:206,headers:{'Content-Range':`bytes ${start}-${start+part-1}/${total}`,ETag:'"stable"'}})};
 try{await download({id:'parallel',version:'v',size:total,sha256:hash,offset:0,handle},async()=>({url:'https://storage.test',version:'v',size:total,sha256:hash}),new AbortController().signal,()=>{},async()=>{});assert.equal(peak,3);assert.deepEqual(seeks,[0,part,2*part]);}finally{globalThis.fetch=original}
});
