import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fingerprint,upload} from '../lib/transfer.ts';
test('streaming fingerprint matches published SHA-256 vector',async()=>{assert.equal(await fingerprint(new Blob(['abc'])),'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=');});
test('pool bounds concurrent PUTs and completes only after all bytes',async()=>{
 const original=globalThis.fetch;let active=0,peak=0,puts=0,completed=false,bytes=0;
 globalThis.fetch=async()=>{active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,10));puts++;active--;return new Response('',{status:200})};
 try{const file=new File(['abcdefghij'],'a.txt');await upload(file,{id:'one',part_size:2,size:10,state:'UPLOADING'},async(path)=>{if(path.endsWith('/complete')){assert.equal(puts,5);completed=true;return {}}return {url:'https://storage.test',headers:{}}},new AbortController().signal,n=>bytes=n);assert.equal(peak,3);assert.equal(bytes,10);assert.equal(completed,true);}finally{globalThis.fetch=original;}
});
test('aborted transfer never finalizes',async()=>{const c=new AbortController();c.abort();let calls=0;await assert.rejects(()=>upload(new File(['x'],'a'),{id:'two',part_size:2,size:1,state:'UPLOADING'},async()=>{calls++;},c.signal,()=>{}));assert.equal(calls,0);});
