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
  // Screens are lazy-loaded and switched in a transition, so wait until the menu shows the new screen as active.
  const route=async(hash)=>{await evaluate('location.hash='+JSON.stringify(hash));await waitFor('document.querySelector(".side-nav a.active")?.getAttribute("href")==='+JSON.stringify('#'+hash)+' || !!document.querySelector("#app > .landing")');await wait();};
  const screenshot=async(name)=>{const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(path.join(out,name+'.png'),Buffer.from(shot.data,'base64'));};
  // React ignores `field.value = x` (it tracks that setter), so simulated typing goes through the native one.
  const setValueJs='(field,value)=>Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field),"value").set.call(field,value)';
  const noOverflow=async()=>assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'),true,'Horizontal overflow');
  await call('Runtime.enable');await call('Page.enable');
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:process.env.ONFIT_URL||'http://localhost:5173'});
  await new Promise(r=>setTimeout(r,800));
  await evaluate('localStorage.clear()');
  await call('Page.reload'); await new Promise(r=>setTimeout(r,500));
  await evaluate('Promise.race([document.fonts.ready,new Promise(r=>setTimeout(r,1500))])');
  assert.ok(await evaluate('document.querySelector(".landing-hero") !== null'));
  await noOverflow(); await screenshot('landing-desktop');
  await click('[data-action="login"]');
  assert.equal(await evaluate('document.querySelectorAll(".oauth").length'),4);
  for (const provider of ['google', 'naver', 'kakao']) {
    assert.ok(await evaluate(`document.querySelector('[data-action="oauth-${provider}"]') !== null`));
  }
  assert.equal(await evaluate('document.querySelector("[data-action=blank-demo], [data-action=sample-demo]")'),null);
  if (process.env.ONFIT_EXPECT_AUTH_CONFIG === '1') {
    assert.equal(await evaluate('document.querySelector("[data-action=oauth-login]").disabled'),false);
    if (!process.env.ONFIT_E2E_USERNAME) {
      await click('[data-action="oauth-login"]');
      await waitFor('!!document.querySelector("#username")');
      assert.equal(await evaluate('location.pathname.includes("/realms/onfit/protocol/openid-connect/auth")'),true);
      assert.equal(await evaluate('!!document.querySelector("#kc-registration a")'),true);
    }
  }
  if (process.env.ONFIT_E2E_USERNAME && process.env.ONFIT_E2E_PASSWORD) {
  assert.equal(await evaluate('document.querySelector("[data-action=oauth-login]").disabled'),false);
  await click('[data-action="oauth-login"]');
  await waitFor('!!document.querySelector("#username")');
  await evaluate('document.querySelector("#username").value='+JSON.stringify(process.env.ONFIT_E2E_USERNAME));
  await evaluate('document.querySelector("#password").value='+JSON.stringify(process.env.ONFIT_E2E_PASSWORD));
  await click('#kc-login');
  try { await waitFor('!!document.querySelector(".app-shell")'); }
  catch (error) {
    const page = await evaluate('({ page: location.origin + location.pathname, title: document.title, message: document.querySelector(".alert-error, .instruction, [role=alert]")?.textContent?.trim()?.slice(0, 200) })');
    throw new Error(`${error.message}: ${JSON.stringify(page)}`);
  }
  await route('/profile');
  await waitFor('document.querySelector("#profile-form") !== null');
  await evaluate("document.querySelector('[name=projectName]').value='브라우저 테스트 프로젝트';document.querySelector('[name=projectDescription]').value='Java API와 PostgreSQL 데이터베이스를 설계하고 AWS에 배포했습니다.';document.querySelector('[name=projectStack]').value='Java, Spring Boot, PostgreSQL, Docker, AWS';document.querySelector('[name=types][value=핀테크]').checked=true");
  await click('[data-action="add-project"]');
  assert.equal(await evaluate('document.querySelectorAll(".project-block").length'),2);
  await click('.project-block:last-child [data-action="remove-project"]');
  const pendingSave = await evaluate(`(() => {
    const originalFetch = window.fetch.bind(window);
    window.__profileWriteCount = 0;
    window.fetch = (...args) => {
      const [url, options] = args;
      if (String(url).startsWith('/api/profiles') && ['PUT', 'POST'].includes(options?.method)) {
        window.__profileWriteCount++;
        if (!window.__releaseProfileSave) {
          return new Promise(resolve => { window.__releaseProfileSave = () => resolve(originalFetch(...args)); });
        }
      }
      return originalFetch(...args);
    };
    const form = document.querySelector('#profile-form');
    form.requestSubmit();
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    return { writes: window.__profileWriteCount,
      disabled: form.querySelector('[type="submit"]').disabled,
      busy: form.getAttribute('aria-busy') };
  })()`);
  assert.deepEqual({ disabled: pendingSave.disabled, busy: pendingSave.busy }, { disabled: true, busy: 'true' });
  await waitFor('window.__profileWriteCount === 1');
  await evaluate('window.__releaseProfileSave()');
  await waitFor('document.querySelectorAll(".job-card").length > 0');
  assert.ok(await evaluate('document.querySelectorAll(".job-card").length > 0'));
  await screenshot('recommendations-desktop');
  const koreanSearch = await evaluate(`(() => {
    const setValue = ${setValueJs};
    const field = document.querySelector('#company-search');
    field.focus();
    field.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    setValue(field, 'ㄱ');
    field.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true, data: 'ㄱ' }));
    const sameDuringComposition = document.querySelector('#company-search') === field;
    setValue(field, '가온');
    field.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true, data: '가온' }));
    field.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '가온' }));
    field.dispatchEvent(new InputEvent('input', { bubbles: true, data: '가온' }));
    const result = { sameDuringComposition, sameAfterComposition: document.querySelector('#company-search') === field,
      value: field.value, count: document.querySelectorAll('.job-card').length,
      companies: [...document.querySelectorAll('.job-card .company-line h3')].map(h => h.textContent) };
    setValue(field, '');
    field.dispatchEvent(new InputEvent('input', { bubbles: true }));
    return result;
  })()`);
  assert.equal(koreanSearch.sameDuringComposition, true);
  assert.equal(koreanSearch.sameAfterComposition, true);
  assert.equal(koreanSearch.value, '가온');
  assert.ok(koreanSearch.count > 0);
  assert.ok(koreanSearch.companies.every(company => company.includes('가온')), 'Search filters the cards');
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
  await evaluate('('+setValueJs+')(document.querySelector("#jd-text"),"자격요건: Java, Spring Boot, PostgreSQL을 사용한 개발 경험");document.querySelector("#jd-text").dispatchEvent(new Event("input",{bubbles:true}))');
  assert.equal(await evaluate('document.querySelector("#analyze-button").disabled'),true);
  await click('[data-action="confirm-text"]');
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
  assert.ok((await evaluate('document.querySelector("#jd-text").value')).includes('Java Spring Boot'));
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
  try { await waitFor('document.querySelectorAll(".job-card").length > 0'); }
  catch (error) {
    const page = await evaluate('({ page: location.origin + location.pathname, params: [...new URLSearchParams(location.search).keys()], redirect: new URLSearchParams(location.search).get("redirect_uri"), hash: location.hash.startsWith("#/") ? location.hash : "auth callback", title: document.title, heading: document.querySelector("h1")?.textContent?.trim()?.slice(0, 100), app: !!document.querySelector(".app-shell"), login: !!document.querySelector("[data-action=login]"), message: document.body.innerText?.trim()?.slice(0, 200) })');
    throw new Error(`${error.message}: ${JSON.stringify(page)}`);
  }
  await call('Emulation.setDeviceMetricsOverride',{width:320,height:740,deviceScaleFactor:1,mobile:true});
  for(const hash of ['/','/profile','/recommendations','/analyze']) {await route(hash);await noOverflow();}
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await route('/recommendations');
  assert.equal(await evaluate('Object.keys(localStorage).some(key => /token|demoSession/.test(key))'),false);
  await click('[data-action="logout"]');
  await waitFor('!!document.querySelector(".landing-hero")');
  assert.equal(await evaluate('!!document.querySelector(".app-shell")'),false);
  assert.equal(await evaluate('fetch("/api/profiles/me").then(response => response.status)'),401);
  assert.deepEqual(errors,[]);
  console.log('PASS: Keycloak login/logout, authenticated API, session restore, desktop/mobile regression, no runtime exceptions.');
  } else {
    assert.deepEqual(errors,[]);
    console.log('PASS: public landing and OAuth-only login; set ONFIT_E2E_USERNAME/PASSWORD for full authenticated regression.');
  }
  console.log('Screenshots: '+out);
} finally {clearTimeout(timer);socket?.close();chrome.kill();}

