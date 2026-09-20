// Fictional fixtures and deterministic demo calculations. Replace with API calls.
export const sampleProfile = {
  types: ['스타트업', '중견기업'], companies: '모노랩, 오르빗',
  role: '백엔드 개발자', department: '플랫폼 개발팀', career: '신입', years: '',
  projects: [{ name: '나만의 채용 추천 서비스', description: '사용자 프로필과 공고를 비교하는 추천 서비스를 개발했습니다. Spring Boot API와 PostgreSQL 데이터 모델을 설계하고 Docker로 AWS에 배포했습니다.', stack: 'Java, Spring Boot, PostgreSQL, Docker, AWS' }],
};
export const jobs = [
  { id: 'mono', company: '모노랩', initial: 'm', color: 'sage', type: '스타트업', role: '백엔드 개발자', team: '커머스 플랫폼팀', location: '서울 성수', career: '신입', title: '함께 성장할 백엔드 개발자', intro: '일상의 작은 브랜드와 사람을 연결하는 커머스 플랫폼을 만들어요.', required: ['Java', 'Spring Boot', 'PostgreSQL'], preferred: ['Docker', 'Redis'], duties: '커머스 서비스 API 개발과 데이터 모델 설계, 안정적인 서비스 운영', tags: ['유연근무', '성장 지원'] },
  { id: 'orbit', company: '오르빗', initial: 'ø', color: 'peach', type: '중견기업', role: '백엔드 개발자', team: '플랫폼 개발팀', location: '서울 판교 인근', career: '신입', title: '서비스의 기반을 만드는 서버 개발자', intro: '더 편리한 이동을 위한 서비스를 함께 만들 동료를 찾고 있어요.', required: ['Java', 'Spring Boot', 'MySQL'], preferred: ['AWS', 'Docker'], duties: '모빌리티 플랫폼 API 개발, 서비스 성능 개선 및 모니터링', tags: ['하이브리드', '교육비 지원'] },
  { id: 'flow', company: '플로우웍스', initial: 'f', color: 'lavender', type: '스타트업', role: '백엔드 개발자', team: '제품 개발팀', location: '서울 강남', career: '신입', title: '팀의 일을 더 가볍게 만드는 개발자', intro: '팀이 본질에 집중할 수 있는 협업 도구를 만들고 있습니다.', required: ['Python', 'FastAPI', 'PostgreSQL'], preferred: ['Docker', 'AWS'], duties: '협업 서비스 API 개발 및 외부 서비스 연동', tags: ['원격근무', '자율 출퇴근'] },
  { id: 'terra', company: '테라뱅크', initial: 't', color: 'sand', type: '대기업', role: '백엔드 개발자', team: '디지털 금융팀', location: '서울 여의도', career: '신입', title: '새로운 금융 경험을 만드는 개발자', intro: '누구나 쉽게 사용할 수 있는 금융 경험을 설계해요.', required: ['Java', 'Spring Boot', 'SQL'], preferred: ['AWS', 'Redis'], duties: '금융 서비스 개발 및 트랜잭션 안정성 개선', tags: ['복지 포인트', '체계적 온보딩'] },
  { id: 'pixel', company: '픽셀스튜디오', initial: 'p', color: 'rose', type: '스타트업', role: '프론트엔드 개발자', team: '웹 경험팀', location: '서울 마포', career: '신입', title: '사용자 경험을 함께 만드는 프론트엔드 개발자', intro: '복잡한 일을 단순하게 만드는 웹 서비스를 만듭니다.', required: ['React', 'TypeScript', 'CSS'], preferred: ['JavaScript', 'Git'], duties: '반응형 웹 UI 개발과 접근성 개선', tags: ['디자인 협업', '유연근무'] },
  { id: 'data', company: '데이터그로브', initial: 'd', color: 'sage', type: '중견기업', role: '데이터 엔지니어', team: '데이터 플랫폼팀', location: '서울 서초', career: '경력', title: '데이터 플랫폼 엔지니어', intro: '데이터에서 다음 비즈니스의 가능성을 발견합니다.', required: ['Python', 'SQL', 'Spark'], preferred: ['AWS', 'Docker'], duties: '데이터 파이프라인 구축과 품질 관리', tags: ['기술 세미나', '하이브리드'] },
];
export const knownSkills = ['Java', 'JavaScript', 'TypeScript', 'Spring Boot', 'PostgreSQL', 'MySQL', 'Python', 'FastAPI', 'Docker', 'AWS', 'Redis', 'React', 'SQL', 'CSS', 'Git', 'Kubernetes', 'Node.js', 'Spark', 'MongoDB'];
const normalize = value => value.toLowerCase().replace(/\s+/g, ' ').trim();
export const profileSkills = profile => [...new Set(profile.projects.flatMap(p => p.stack.split(/[,，\n]/)).map(s => s.trim()).filter(Boolean))];
export function compareSkills(required, skills) {
  const normalized = new Set(skills.map(normalize));
  const matched = required.filter(s => normalized.has(normalize(s)));
  return { matched, missing: required.filter(s => !normalized.has(normalize(s))), score: required.length ? Math.round(matched.length / required.length * 100) : null };
}
export function matchJob(job, profile) {
  const skills = profileSkills(profile);
  const required = compareSkills(job.required, skills);
  const preferred = compareSkills(job.preferred, skills);
  const roleScore = profile.role === job.role ? 100 : 0;
  const preferenceScore = !profile.types.length || profile.types.includes(job.type) ? 100 : 0;
  const score = Math.round(required.score * .55 + preferred.score * .25 + roleScore * .15 + preferenceScore * .05);
  return { ...job, score, requiredMatch: required, preferredMatch: preferred };
}
export function detectSkills(text) {
  return knownSkills.filter(skill => {
    const escaped = skill.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp('(?<![a-zA-Z0-9])' + escaped + '(?![a-zA-Z0-9])', 'i').test(text);
  });
}
export function analyzeText(text, profile) {
  let section = 'unknown';
  const grouped = { required: [], preferred: [], unknown: [] };
  for (const line of text.split('\n')) {
    if (/우대|preferred|nice to have/i.test(line)) section = 'preferred';
    else if (/필수|자격요건|자격 요건|requirements|required/i.test(line)) section = 'required';
    else if (/담당|주요 업무|직무|responsibilities/i.test(line)) section = 'unknown';
    grouped[section].push(...detectSkills(line));
  }
  for (const key of Object.keys(grouped)) grouped[key] = [...new Set(grouped[key])];
  const skills = profileSkills(profile);
  const all = [...new Set(Object.values(grouped).flat())];
  return { all, overall: compareSkills(all, skills), required: compareSkills(grouped.required, skills), preferred: compareSkills(grouped.preferred, skills), unknown: grouped.unknown };
}
export const sampleJD = '모노랩 | 백엔드 개발자 (신입)\n\n주요 업무\n커머스 서비스 API 개발과 데이터 모델 설계\n\n자격요건\n- Java, Spring Boot 기반의 프로젝트 경험\n- PostgreSQL을 사용한 데이터베이스 설계 경험\n\n우대사항\n- Docker 기반 서비스 배포 경험\n- Redis를 활용한 캐시 구현 경험';

