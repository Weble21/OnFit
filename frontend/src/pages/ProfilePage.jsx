import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { industries, regions, roles, splitSkills } from '../data.js';
import { Icon, PageHeading } from '../components/ui.jsx';

const emptyProfile = () => ({ types: [], companies: '', role: '백엔드 개발자', location: '', department: '', career: '신입', experiences: [], projects: [{ name: '', description: '', stack: '' }] });
const emptyProject = () => ({ name: '', description: '', stack: '' });
const emptyExperience = () => ({ company: '', role: '백엔드 개발자', start: '', end: '' });
let nextKey = 0;
const withKey = item => ({ ...item, key: ++nextKey });

function Options({ values, current, blank }) {
  // Keep a stored value that is no longer offered, so saving the form does not silently change it.
  const list = current && !values.includes(current) ? [...values, current] : values;
  return <>{blank && <option value="">{blank}</option>}{list.map(value => <option key={value}>{value}</option>)}</>;
}

function ExperienceFields({ experience, index, onRemove }) {
  return (
    <section className="experience-block">
      <div className="project-heading">
        <h3>경력 {index + 1}</h3>
        <button className="btn text danger" type="button" data-action="remove-experience" hidden={index === 0} onClick={onRemove}>삭제</button>
      </div>
      <div className="form-row">
        <label>회사명 <span>*</span><input name="experienceCompany" required maxLength={200} placeholder="예: 온핏 주식회사" defaultValue={experience.company} /></label>
        <label>담당 직무 <span>*</span><select name="experienceRole" required defaultValue={experience.role}><Options values={roles} current={experience.role} /></select></label>
      </div>
      <div className="form-row">
        <label>시작 월 <span>*</span><input name="experienceStart" type="month" required defaultValue={experience.start} /></label>
        <label>종료 월 <span className="optional">재직 중이면 비워 두세요</span><input name="experienceEnd" type="month" defaultValue={experience.end} /></label>
      </div>
    </section>
  );
}

function ProjectFields({ project, index, onRemove }) {
  return (
    <section className="project-block" data-project={index}>
      <div className="project-heading">
        <h3>프로젝트 {index + 1}</h3>
        <button className="btn text danger" type="button" data-action="remove-project" hidden={index === 0} onClick={onRemove}>삭제</button>
      </div>
      <label>프로젝트명 <span>*</span><input name="projectName" required maxLength={100} placeholder="예: 개인 맞춤 채용 추천 서비스" defaultValue={project.name} /></label>
      <label>프로젝트 설명과 나의 역할 <span>*</span><textarea name="projectDescription" required maxLength={3000} rows={4} placeholder="어떤 문제를 해결했나요? 직접 맡은 역할과 성과를 알려주세요." defaultValue={project.description} /></label>
      <label>사용한 기술 스택 <span>*</span><input name="projectStack" required maxLength={500} placeholder="Java, Spring Boot, PostgreSQL처럼 쉼표로 구분해 주세요." defaultValue={project.stack} /></label>
    </section>
  );
}

/**
 * Inputs are uncontrolled and read once on submit. Project and experience lists keep their own
 * entries only for add/remove and for server fields the form does not edit (`extra`).
 */
export function ProfilePage({ profile, onSave, onToast }) {
  const initial = profile || emptyProfile();
  const [career, setCareer] = useState(initial.career);
  const [projects, setProjects] = useState(() => initial.projects.map(withKey));
  const [experiences, setExperiences] = useState(() => (initial.experiences?.length ? initial.experiences : [emptyExperience()]).map(withKey));
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const focusNew = useRef(null);

  useEffect(() => {
    if (!focusNew.current) return;
    document.querySelector(focusNew.current + ' > section:last-child input')?.focus();
    focusNew.current = null;
  }, [projects, experiences]);

  async function submit(event) {
    event.preventDefault();
    if (savingRef.current) return;
    const form = event.currentTarget;
    for (const field of form.querySelectorAll('[required]:not(:disabled)')) {
      if (!field.value.trim()) { field.setCustomValidity('공백이 아닌 내용을 입력해 주세요.'); field.reportValidity(); return; }
    }
    const data = new FormData(form);
    const column = name => data.getAll(name).map(value => String(value).trim());
    const [names, descriptions, stacks] = ['projectName', 'projectDescription', 'projectStack'].map(column);
    const nextProjects = projects.map((project, i) => ({ name: names[i], description: descriptions[i], stack: stacks[i], extra: project.extra || {} }));
    if (nextProjects.some(project => !splitSkills(project.stack).length)) { onToast('프로젝트에 기술을 한 개 이상 입력해 주세요.'); return; }
    // A disabled fieldset leaves the experience inputs out of FormData, which is what 신입 means.
    const [companies, experienceRoles, starts, ends] = ['experienceCompany', 'experienceRole', 'experienceStart', 'experienceEnd'].map(column);
    const nextExperiences = career === '경력'
      ? experiences.map((experience, i) => ({ company: companies[i], role: experienceRoles[i], start: starts[i], end: ends[i], extra: experience.extra || {} }))
      : [];
    if (nextExperiences.some(experience => experience.end && experience.end < experience.start)) { onToast('경력 종료 월은 시작 월보다 빠를 수 없어요.'); return; }
    const next = {
      types: data.getAll('types'), companies: String(data.get('companies')).trim(), role: data.get('role'),
      location: data.get('location') || '', department: String(data.get('department')).trim(),
      career, experiences: nextExperiences, certificates: profile?.certificates || [], projects: nextProjects,
    };
    savingRef.current = true;
    // Disable the button before this handler returns so a second click cannot slip in.
    flushSync(() => setSaving(true));
    try { await onSave(next); }
    finally { savingRef.current = false; setSaving(false); }
  }

  const addProject = () => { focusNew.current = '#projects'; setProjects(list => [...list, withKey(emptyProject())]); };
  const addExperience = () => { focusNew.current = '#experiences'; setExperiences(list => [...list, withKey(emptyExperience())]); };

  return (
    <>
      <PageHeading kicker="YOUR STORY STARTS HERE" title="당신의 이야기를 들려주세요." description="작은 경험도 좋은 기회의 시작이 될 수 있어요. 언제든 수정할 수 있습니다." />
      <div className="profile-layout">
        <form id="profile-form" className="profile-form" aria-label="커리어 프로필" aria-busy={saving ? 'true' : undefined}
          onSubmit={submit} onInput={event => event.target.setCustomValidity?.('')}>
          <section className="form-section" aria-labelledby="profile-step-1">
            <header className="form-section-heading"><span aria-hidden="true">01</span><div><h2 id="profile-step-1">어떤 분야에서 일하고 싶나요?</h2><p>관심 업종은 여러 개 고를 수 있어요. 추천 화면의 업종 필터에서 앞쪽에 보여 줘요.</p></div></header>
            <fieldset className="type-options industry-options">
              <legend className="sr-only">관심 업종</legend>
              {industries.map(industry => (
                <label key={industry} className="type-option">
                  <input type="checkbox" name="types" value={industry} defaultChecked={initial.types.includes(industry)} />
                  <span><strong>{industry}</strong><Icon name="check" /></span>
                </label>
              ))}
            </fieldset>
            <label>관심 있는 기업 <span className="optional">선택</span><input name="companies" maxLength={500} defaultValue={initial.companies} placeholder="기업명을 쉼표로 구분해 입력해 주세요." /></label>
            <p className="field-help">관심 업종과 기업명은 이 브라우저에만 저장되며 추천 점수에는 반영되지 않아요.</p>
          </section>
          <section className="form-section" aria-labelledby="profile-step-2">
            <header className="form-section-heading"><span aria-hidden="true">02</span><div><h2 id="profile-step-2">어떤 일을 하고 싶나요?</h2><p>앞으로 나아가고 싶은 방향을 알려주세요.</p></div></header>
            <div className="form-row">
              <label>목표 직무 <span>*</span><select name="role" required defaultValue={initial.role}><Options values={roles} current={initial.role} /></select></label>
              <label>목표 부서 <span className="optional">선택</span><input name="department" maxLength={100} defaultValue={initial.department} placeholder="예: 플랫폼 개발팀, 금융 IT팀" /></label>
              <label>희망 지역 <span className="optional">선택</span><select name="location" defaultValue={initial.location}><Options values={regions} current={initial.location} blank="선택 안 함" /></select></label>
            </div>
            <fieldset className="career-options">
              <legend>경력 구분 <span>*</span></legend>
              {['신입', '경력'].map(value => (
                <label key={value}><input type="radio" name="career" value={value} defaultChecked={initial.career === value} onChange={() => setCareer(value)} /> {value}</label>
              ))}
            </fieldset>
            <fieldset id="experience-field" className="experience-options" hidden={career !== '경력'} disabled={career !== '경력'}>
              <legend>경력 사항 <span>*</span></legend>
              <div id="experiences">
                {experiences.map((experience, index) => (
                  <ExperienceFields key={experience.key} experience={experience} index={index}
                    onRemove={() => setExperiences(list => list.filter(item => item.key !== experience.key))} />
                ))}
              </div>
              <button className="btn outline add-project" type="button" data-action="add-experience" onClick={addExperience}><Icon name="plus" /> 경력 추가</button>
            </fieldset>
          </section>
          <section className="form-section" aria-labelledby="profile-step-3">
            <header className="form-section-heading"><span aria-hidden="true">03</span><div><h2 id="profile-step-3">어떤 경험을 쌓아왔나요?</h2><p>직접 만들고, 고민하고, 해결했던 경험을 적어주세요.</p></div></header>
            <div id="projects">
              {projects.map((project, index) => (
                <ProjectFields key={project.key} project={project} index={index}
                  onRemove={() => setProjects(list => list.filter(item => item.key !== project.key))} />
              ))}
            </div>
            <button className="btn outline add-project" type="button" data-action="add-project" onClick={addProject}><Icon name="plus" /> 프로젝트 추가</button>
          </section>
          <div className="form-actions">
            <span>직무·지역·경력·기술·프로젝트는 서버에 저장됩니다. 관심 업종·기업명은 브라우저에 저장됩니다.</span>
            <button className="btn" type="submit" disabled={saving}>프로필 저장하고 추천 보기 <Icon name="arrow" /></button>
          </div>
        </form>
        <aside className="profile-guide" aria-labelledby="profile-guide-title">
          <span className="round-icon" aria-hidden="true"><Icon name="spark" /></span>
          <h2 id="profile-guide-title">경험의 크기보다<br />나의 역할이 중요해요.</h2>
          <p>팀 프로젝트, 개인 프로젝트,<br />새로운 시도 모두 좋아요.</p>
          <figure className="guide-example"><figcaption><small>이렇게 적어보세요</small></figcaption><blockquote><p>“쇼핑몰 프로젝트에서 주문 API를 개발하고, 쿼리를 개선해 응답 시간을 줄였어요.”</p></blockquote></figure>
          <ul aria-label="작성 포인트"><li><Icon name="check" /> 직접 맡은 역할</li><li><Icon name="check" /> 문제를 해결한 과정</li><li><Icon name="check" /> 사용한 기술과 얻은 결과</li></ul>
          <div className="guide-bottom">온핏은 등록한 경험을 바탕으로<br />당신의 가능성을 찾아갑니다.</div>
        </aside>
      </div>
    </>
  );
}
