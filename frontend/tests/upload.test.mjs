import test from 'node:test';
import assert from 'node:assert/strict';
import {validateAttachment, MAX_FILE_BYTES} from '../src/upload.js';
test('recognizes supported file signatures, including an untyped PDF',async()=>{
  const inputs=[
    ['a.png','image/png',new Uint8Array([137,80,78,71,13,10,26,10]),'image'],
    ['a.jpg','image/jpeg',new Uint8Array([255,216,255]),'image'],
    ['a.webp','image/webp','RIFF0000WEBP','image'],
    ['a.pdf','','%PDF-1.4\n','pdf']
  ];
  for(const [name,type,body,kind] of inputs) assert.equal((await validateAttachment(new File([body],name,{type}))).kind,kind);
});
test('rejects empty, oversize, unsupported, mismatched and disguised files',async()=>{
  await assert.rejects(validateAttachment(new File([],'empty.pdf')),/비어/);
  await assert.rejects(validateAttachment({size:MAX_FILE_BYTES+1}),/10MB/);
  await assert.rejects(validateAttachment(new File(['hello'],'a.txt',{type:'text/plain'})),/JPG/);
  await assert.rejects(validateAttachment(new File(['%PDF-1.4'],'a.png',{type:'image/png'})),/형식/);
  await assert.rejects(validateAttachment(new File(['%PDF-1.4'],'a.pdf',{type:'image/png'})),/JPG/);
});

