// Demo profile, form choices and the keyword comparison behind free-text JD analysis.
export const sampleProfile = {
  types: ['스타트업', '중견기업'], companies: '모노랩, 오르빗',
  role: '백엔드 개발자', location: '서울', department: '플랫폼 개발팀', career: '신입', experiences: [],
  projects: [{ name: '나만의 채용 추천 서비스', description: '사용자 프로필과 공고를 비교하는 추천 서비스를 개발했습니다. Spring Boot API와 PostgreSQL 데이터 모델을 설계하고 Docker로 AWS에 배포했습니다.', stack: 'Java, Spring Boot, PostgreSQL, Docker, AWS' }],
};
// Server scoring compares role and region names exactly, so these must cover the seed postings' values.
export const roles = ['백엔드 개발자', '프론트엔드 개발자', '모바일 개발자', '데이터 엔지니어', '데이터 분석가', 'AI 엔지니어', 'DevOps 엔지니어', '보안 엔지니어', 'QA 엔지니어', '게임 개발자', '로봇 개발자', '기타'];
export const regions = ['서울', '경기', '인천', '부산', '대구', '광주', '대전', '울산', '세종', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];
export const knownSkills = ['Java', 'JavaScript', 'TypeScript', 'Spring Boot', 'PostgreSQL', 'MySQL', 'Python', 'FastAPI', 'Docker', 'AWS', 'Redis', 'React', 'SQL', 'CSS', 'Git', 'Kubernetes', 'Node.js', 'Spark', 'MongoDB'];
const normalize = value => value.toLowerCase().replace(/\s+/g, ' ').trim();
export const profileSkills = profile => [...new Set(profile.projects.flatMap(p => p.stack.split(/[,，\n]/)).map(s => s.trim()).filter(Boolean))];
export function compareSkills(required, skills) {
  const normalized = new Set(skills.map(normalize));
  const matched = required.filter(s => normalized.has(normalize(s)));
  return { matched, missing: required.filter(s => !normalized.has(normalize(s))), score: required.length ? Math.round(matched.length / required.length * 100) : null };
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

