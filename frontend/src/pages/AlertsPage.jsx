import { useEffect, useState } from 'react';
import { getAlerts, saveAlerts, requestAlertEmail, verifyAlertEmail } from '../api.js';
import { PageHeading } from '../components/ui.jsx';
import { expandCompanies } from '../companyGroups.js';

export function AlertsPage() {
  const [settings, setSettings] = useState(null);
  const [email, setEmail] = useState('');
  const [companies, setCompanies] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  function apply(value) { setSettings(value); setEmail(value.email); setCompanies(value.companies.join('\n')); }
  useEffect(() => {
    let active = true;
    getAlerts().then(value => { if (active) apply(value); }, err => { if (active) setError(err.message); });
    return () => { active = false; };
  }, []);
  async function act(work, success) {
    setBusy(true); setError(''); setMessage('');
    try { await work(); setMessage(success); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  return <>
    <PageHeading title="채용 공고 이메일 알림" description="매일 오전 11시와 오후 5시(한국 시간)에 공고를 확인하고, 새로운 맞춤 공고를 이메일로 알려드려요." />
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {!settings && !error && <p role="status">알림 설정을 불러오는 중입니다.</p>}
    {settings && <section className="profile-form form-section" aria-busy={busy}>
      {!settings.mailAvailable && <p>이메일 알림 서비스를 준비 중입니다. 지금은 관심 기업 설정을 저장할 수 있어요.</p>}
      <form onSubmit={event => { event.preventDefault(); act(async () => {
        await requestAlertEmail(email); apply(await getAlerts());
      }, '인증번호를 보냈어요. 이메일에서 확인해 주세요.'); }}>
        <label>알림 받을 이메일<input type="email" required maxLength={320} value={email} onChange={e => setEmail(e.target.value)} autoComplete="email" /></label>
        <p>{settings.verified ? '이메일 인증 완료' : '이메일 인증이 필요합니다.'}</p>
        <p>같은 이메일 주소로 보내는 인증 메일은 하루 5회까지 요청할 수 있어요. 한국 시간 자정에 초기화됩니다.</p>
        <button className="btn secondary" disabled={busy || !settings.mailAvailable}>인증번호 보내기</button>
      </form>
      {!settings.verified && <form onSubmit={event => { event.preventDefault(); act(async () => {
        apply(await verifyAlertEmail(code)); setCode('');
      }, '이메일 인증을 완료했어요. 알림 수신을 선택하고 저장해 주세요.'); }}>
        <label>인증번호<input required inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={e => setCode(e.target.value)} autoComplete="one-time-code" /></label>
        <button className="btn secondary" disabled={busy || !settings.mailAvailable}>인증 확인</button>
      </form>}
      <form onSubmit={event => { event.preventDefault(); act(async () => {
        apply(await saveAlerts({ enabled: settings.enabled,
          companies: companies.split('\n').map(name => name.trim()).filter(Boolean), minScore: Number(settings.minScore) }));
      }, '알림 설정을 저장했어요.'); }}>
        <label>관심 기업<textarea rows={5} maxLength={4000} value={companies} onChange={e => setCompanies(e.target.value)} placeholder={'네이버\n카카오'} /></label>
        <p>기업명을 한 줄에 하나씩, 최대 20개 입력해 주세요. 카카오는 카카오페이·카카오뱅크·카카오모빌리티까지 함께 수집·추천합니다.</p>
        {companies.trim() && <p role="status">수집·알림 대상: {expandCompanies(companies.split('\n')).join(', ')}</p>}
        <label>최소 추천 점수<input type="number" min={0} max={100} step={1} required value={settings.minScore}
          onChange={e => setSettings({ ...settings, minScore: e.target.value })} /></label>
        <label><input type="checkbox" checked={settings.enabled} disabled={!settings.verified}
          onChange={e => setSettings({ ...settings, enabled: e.target.checked })} />새로운 맞춤 공고 이메일 수신에 동의합니다.</label>
        <p>수신 시작 이후 새로 수집한 공고를 보내드립니다. 수신 동의를 해제하고 저장하면 알림이 중단됩니다.</p>
        <button className="btn primary" disabled={busy}>설정 저장</button>
      </form>
    </section>}
  </>;
}
