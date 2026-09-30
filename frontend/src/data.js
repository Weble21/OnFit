// Demo profile, form choices and the keyword comparison behind free-text JD analysis.
export const sampleProfile = {
  types: ['클라우드', '핀테크'], companies: '모노랩, 오르빗',
  role: '백엔드 개발자', location: '서울', department: '플랫폼 개발팀', career: '신입', experiences: [],
  projects: [{ name: '나만의 채용 추천 서비스', description: '사용자 프로필과 공고를 비교하는 추천 서비스를 개발했습니다. Spring Boot API와 PostgreSQL 데이터 모델을 설계하고 Docker로 AWS에 배포했습니다.', stack: 'Java, Spring Boot, PostgreSQL, Docker, AWS' }],
};
// Server scoring compares role and region names exactly, so these must cover the seed postings' values.
export const roles = ['백엔드 개발자', '프론트엔드 개발자', '모바일 개발자', '데이터 엔지니어', '데이터 분석가', 'AI 엔지니어', 'DevOps 엔지니어', '보안 엔지니어', 'QA 엔지니어', '게임 개발자', '로봇 개발자', '기타'];
// Posting industry values must be covered by the profile's interest options.
export const industries = ['클라우드', '핀테크', '모빌리티', '이커머스', '헬스케어', '에듀테크', '게임', '물류', '보안', '미디어', '여행', '에너지', '제조', '프롭테크', '애그리테크', '로보틱스', '인슈어테크', '소셜', '리테일'];
export const regions = ['서울', '경기', '인천', '부산', '대구', '광주', '대전', '울산', '세종', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];
export const knownSkills = ['Java', 'JavaScript', 'TypeScript', 'Spring Boot', 'PostgreSQL', 'MySQL', 'Python', 'FastAPI', 'Docker', 'AWS', 'Redis', 'React', 'SQL', 'CSS', 'Git', 'Kubernetes', 'Node.js', 'Spark', 'MongoDB'];
// Copy of backend/src/main/resources/skill-aliases.json; tests/skills.test.mjs fails when they drift apart.
export const skillAliases = {
  'springboot': 'spring boot', 'spring-boot': 'spring boot', 'postgres': 'postgresql',
  'amazon web services': 'aws', 'google cloud platform': 'gcp',
  'nodejs': 'node.js', 'node': 'node.js', 'js': 'javascript', 'ts': 'typescript',
  'reactjs': 'react', 'react.js': 'react', 'nextjs': 'next.js', 'vuejs': 'vue', 'vue.js': 'vue',
  'k8s': 'kubernetes', 'golang': 'go', 'mongo': 'mongodb', 'elastic search': 'elasticsearch',
  'sklearn': 'scikit-learn', 'cpp': 'c++', 'csharp': 'c#', 'cicd': 'ci/cd', 'ci / cd': 'ci/cd',
  'restful api': 'rest api', 'oauth 2.0': 'oauth2',
};
// Same rules as the backend SkillNormalizer, so the server never sees names it considers duplicates.
export function normalizeSkill(value) {
  const key = value.trim().replace(/\s+/g, ' ').toLowerCase();
  return skillAliases[key] || key;
}
// "/" is not a separator so that names such as CI/CD stay whole.
export const splitSkills = stack => stack.split(/[,，;\r\n]/).map(s => s.trim()).filter(Boolean);
export function profileSkills(profile) {
  const seen = new Set();
  return profile.projects.flatMap(p => splitSkills(p.stack)).filter(skill => {
    const key = normalizeSkill(skill);
    return !seen.has(key) && seen.add(key);
  });
}
export function compareSkills(required, skills) {
  const normalized = new Set(skills.map(normalizeSkill));
  const matched = required.filter(s => normalized.has(normalizeSkill(s)));
  return { matched, missing: required.filter(s => !normalized.has(normalizeSkill(s))), score: required.length ? Math.round(matched.length / required.length * 100) : null };
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

