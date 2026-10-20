import test from 'node:test';
import assert from 'node:assert/strict';
import {validateAttachment, MAX_FILE_BYTES, pastedFile} from '../src/upload.js';
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
test('pasted screenshots get a readable name that still passes validation',async()=>{
  const png=new Uint8Array([137,80,78,71,13,10,26,10]);
  const shot=pastedFile(new File([png],'image.png',{type:'image/png'}));
  assert.equal(shot.name,'붙여넣은 이미지.png');
  assert.equal((await validateAttachment(shot)).kind,'image');
  assert.equal(pastedFile(new File([png],'',{type:'image/png'})).name,'붙여넣은 이미지.png');
  // A file copied from the file explorer keeps its own name; an unsupported type is left for validation to reject.
  assert.equal(pastedFile(new File([png],'공고.png',{type:'image/png'})).name,'공고.png');
  const gif=new File(['GIF89a'],'image.gif',{type:'image/gif'});
  assert.equal(pastedFile(gif),gif);
  await assert.rejects(validateAttachment(pastedFile(gif)),/JPG, PNG/);
});
