import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const out = path.resolve('frontend/.preview');
await mkdir(out, {recursive:true});
const chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new','--no-first-run','--disable-gpu','--remote-debugging-port=0','--user-data-dir='+path.join(out,'browser-profile'),'about:blank'], {windowsHide:true,stdio:['ignore','ignore','pipe']});
let browserUrl;
let stderr='';
const ready = new Promise((resolve,reject)=>{
  chrome.on('error',reject);
  chrome.stderr.on('data',chunk=>{stderr+=chunk; const match=stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match){browserUrl=match[1];resolve();}});
});
const timer=setTimeout(()=>{chrome.kill();console.error('Browser verification timed out');process.exit(1);},45000);
let socket;
try {
  await ready;
  const port=new URL(browserUrl).port;
  const targets=await (await fetch('http://127.0.0.1:'+port+'/json/list')).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  let seq=0;
  const pending=new Map(), errors=[];
  socket.onmessage=event=>{
    const msg=JSON.parse(event.data);
    if(msg.id){const p=pending.get(msg.id);if(p){pending.delete(msg.id);msg.error?p.reject(new Error(JSON.stringify(msg.error))):p.resolve(msg.result);}}
    if(msg.method==='Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.text);
  };
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async(expression)=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
  const wait=()=>new Promise(r=>setTimeout(r,160));
  const waitFor=async(expression)=>{
    for(let attempt=0;attempt<60;attempt++){
      if(await evaluate(expression)) return;
      await wait();
    }
    throw new Error('Timed out waiting for: '+expression);
  };
  const click=async(selector)=>{await evaluate('document.querySelector('+JSON.stringify(selector)+').click()');await wait();};
  const route=async(hash)=>{await evaluate('location.hash='+JSON.stringify(hash));await wait();};
  const screenshot=async(name)=>{const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(path.join(out,name+'.png'),Buffer.from(shot.data,'base64'));};
  const noOverflow=async()=>assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'),true,'Horizontal overflow');
  await call('Runtime.enable');await call('Page.enable');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:'http://localhost:5173'});
  await new Promise(r=>setTimeout(r,800));
  await evaluate('localStorage.clear()');
  await call('Page.reload'); await new Promise(r=>setTimeout(r,500));
  await evaluate('Promise.race([document.fonts.ready,new Promise(r=>setTimeout(r,1500))])');
  assert.ok(await evaluate('document.querySelector(".landing-hero") !== null'));
  await noOverflow(); await screenshot('landing-desktop');
  await click('[data-action="login"]');
  assert.equal(await evaluate('document.querySelectorAll(".oauth:disabled").length'),3);
  await click('[data-action="blank-demo"]');
  await route('/profile');
  assert.ok(await evaluate('document.querySelector("#profile-form") !== null'));
  await evaluate("document.querySelector('[name=projectName]').value='브라우저 테스트 프로젝트';document.querySelector('[name=projectDescription]').value='Java API와 PostgreSQL 데이터베이스를 설계하고 AWS에 배포했습니다.';document.querySelector('[name=projectStack]').value='Java, Spring Boot, PostgreSQL, Docker, AWS';document.querySelector('[name=types][value=핀테크]').checked=true");
  await click('[data-action="add-project"]');
  assert.equal(await evaluate('document.querySelectorAll(".project-block").length'),2);
  await click('.project-block:last-child [data-action="remove-project"]');
  await evaluate('document.querySelector("#profile-form").requestSubmit()');await wait();
  await waitFor('document.querySelectorAll(".job-card").length > 0');
  assert.ok(await evaluate('document.querySelectorAll(".job-card").length > 0'));
  await screenshot('recommendations-desktop');
  const koreanSearch = await evaluate(`(() => {
    const field = document.querySelector('#company-search');
    field.focus();
    field.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    field.value = 'ㄱ';
    field.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true, data: 'ㄱ' }));
    const sameDuringComposition = document.querySelector('#company-search') === field;
    field.value = '가온';
    field.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true, data: '가온' }));
    field.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '가온' }));
    field.dispatchEvent(new InputEvent('input', { bubbles: true, data: '가온' }));
    const result = { sameDuringComposition, sameAfterComposition: document.querySelector('#company-search') === field,
      value: field.value, count: document.querySelectorAll('.job-card').length,
      company: document.querySelector('.job-card .company-line h3')?.textContent };
    field.value = '';
    field.dispatchEvent(new InputEvent('input', { bubbles: true }));
    return result;
  })()`);
  assert.equal(koreanSearch.sameDuringComposition, true);
  assert.equal(koreanSearch.sameAfterComposition, true);
  assert.equal(koreanSearch.value, '가온');
  assert.ok(koreanSearch.count > 0);
  assert.ok(koreanSearch.company.includes('가온'));
  await click('[data-action="favorite"]');
  await route('/favorites');
  assert.equal(await evaluate('document.querySelectorAll(".job-card").length'),1);
  await click('[data-action="job"]');
  assert.ok(await evaluate('document.querySelector(".job-detail") !== null'));
  await click('[data-action="close-modal"]');
  await route('/analyze');
  await click('[data-action="sample-jd"]');
  await evaluate('document.querySelector("#analysis-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelector(".score-circle strong").textContent'),'80%');
  await screenshot('analysis-desktop');

  // Attachments never imply OCR completion; only reviewed text can be analyzed.
  await click('[data-mode="file"]');
  assert.equal(await evaluate('document.querySelector("#analyze-button").disabled'),true);
  const attach = async files => {
    const {root}=await call('DOM.getDocument');
    const {nodeId}=await call('DOM.querySelector',{nodeId:root.nodeId,selector:'#jd-file'});
    await call('DOM.setFileInputFiles',{nodeId,files});
    await evaluate('new Promise(resolve=>{const poll=()=>document.querySelector("#attachment-zone")?.getAttribute("aria-busy")==="true"?setTimeout(poll,30):resolve();setTimeout(poll,60);})');
  };
  await attach([path.join(out,'landing-desktop.png')]);
  assert.equal(await evaluate('document.querySelector(".image-preview img").naturalWidth>0'),true);
  assert.equal(await evaluate('document.querySelector("#jd-text").value'),'');
  assert.equal(await evaluate('document.querySelector("#analyze-button").disabled'),true);
  await evaluate('document.querySelector("#jd-text").value="자격요건: Java, Spring Boot, PostgreSQL을 사용한 개발 경험";document.querySelector("#jd-text").dispatchEvent(new Event("input",{bubbles:true}))');
  await evaluate('document.querySelector("#analysis-form").requestSubmit()');
  assert.equal(await evaluate('document.querySelector(".score-circle strong").textContent'),'100%');
  await screenshot('upload-image-desktop');
  await click('[data-mode="text"]');
  assert.ok((await evaluate('document.querySelector("#jd-text").value')).includes('모노랩'));
  await click('[data-mode="file"]');
  assert.ok((await evaluate('document.querySelector("#jd-text").value')).includes('자격요건'));
  await writeFile(path.join(out,'unsupported.txt'),'not an image');
  await attach([path.join(out,'unsupported.txt')]);
  assert.ok((await evaluate('document.querySelector("#attachment-error").textContent')).includes('JPG'));
  assert.ok(await evaluate('!!document.querySelector(".image-preview")'),'Invalid replacement preserves original file');
  await evaluate('const transfer=new DataTransfer();transfer.items.add(new File(["%PDF-1.4"],"a.pdf",{type:"application/pdf"}));transfer.items.add(new File(["%PDF-1.4"],"b.pdf",{type:"application/pdf"}));document.querySelector("#attachment-zone").dispatchEvent(new DragEvent("drop",{bubbles:true,cancelable:true,dataTransfer:transfer}));');
  await wait();
  assert.ok((await evaluate('document.querySelector("#attachment-error").textContent')).includes('1개'));
  // A valid, single-page PDF fixture with byte-accurate cross references.
  let pdf='%PDF-1.4\n';
  const offsets=[0];
  const stream='BT /F1 18 Tf 40 250 Td (Job Posting - Java Spring Boot) Tj ET';
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 320] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>','<< /Length '+stream.length+' >>\nstream\n'+stream+'\nendstream'];
  objects.forEach((o,i)=>{offsets.push(Buffer.byteLength(pdf));pdf+=(i+1)+' 0 obj\n'+o+'\nendobj\n';});
  const xref=Buffer.byteLength(pdf);pdf+='xref\n0 6\n0000000000 65535 f \n'+offsets.slice(1).map(o=>String(o).padStart(10,'0')+' 00000 n \n').join('')+'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+xref+'\n%%EOF';
  await writeFile(path.join(out,'posting.pdf'),pdf);
  await attach([path.join(out,'posting.pdf')]);
  assert.ok(await evaluate('!!document.querySelector(".pdf-preview")'));
  assert.equal(await evaluate('document.querySelector("#jd-text").value'),'');
  assert.equal(await evaluate('document.querySelector(".score-circle")'),null);
  assert.equal(await evaluate('document.querySelector("#analyze-button").disabled'),true);
  await noOverflow(); await screenshot('upload-pdf-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  await noOverflow(); await screenshot('upload-mobile');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await click('[data-action="remove-file"]');
  assert.equal(await evaluate('document.querySelector(".pdf-preview")'),null);
  assert.equal(await evaluate('document.querySelector("#jd-text").disabled'),true);
  await click('[data-mode="text"]');

  await route('/profile');
  assert.equal(await evaluate('document.querySelector("#experience-field").disabled'),true);
  await evaluate("document.querySelector('[name=career][value=경력]').click()");
  assert.equal(await evaluate('document.querySelector("[name=experienceCompany]").matches(":required:enabled")'),true);
  await click('[data-action="add-experience"]');
  assert.equal(await evaluate('document.querySelectorAll(".experience-block").length'),2);
  await click('.experience-block:last-child [data-action="remove-experience"]');
  await evaluate("document.querySelector('[name=career][value=신입]').click()");
  assert.equal(await evaluate('document.querySelector("#experience-field").hidden'),true);
  await noOverflow();await screenshot('profile-desktop');
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
  for(const hash of ['/profile','/recommendations','/analyze','/favorites','/']){
    await route(hash);await noOverflow();
    if(hash==='/recommendations')await screenshot('recommendations-mobile');
    if(hash==='/')await screenshot('landing-mobile');
  }
  await route('/recommendations');
  await call('Page.reload');await new Promise(r=>setTimeout(r,500));
  assert.ok(await evaluate('document.querySelectorAll(".job-card").length > 0'),'Profile and route persist on reload');
  await call('Emulation.setDeviceMetricsOverride',{width:320,height:740,deviceScaleFactor:1,mobile:true});
  for(const hash of ['/','/profile','/recommendations','/analyze']) {await route(hash);await noOverflow();}
  assert.deepEqual(errors,[]);
  console.log('PASS: desktop/mobile at 1440, 390, 320px; login, onboarding, project add/remove, profile persistence, recommendations, favorites, job detail, JD analysis, career fields, image/PDF attachments, invalid and multiple files, input isolation, removal, no runtime exceptions.');
  console.log('Screenshots: '+out);
} finally {clearTimeout(timer);socket?.close();chrome.kill();}

