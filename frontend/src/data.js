// Form choices and the sample posting. Posting analysis itself runs on the server.
export const roles = ['백엔드 개발자', '프론트엔드 개발자', '모바일 개발자', '데이터 엔지니어', '데이터 분석가', 'AI 엔지니어', 'DevOps 엔지니어', '보안 엔지니어', 'QA 엔지니어', '게임 개발자', '로봇 개발자', '기타'];
export const regions = ['서울', '경기', '인천', '부산', '대구', '광주', '대전', '울산', '세종', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];
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
