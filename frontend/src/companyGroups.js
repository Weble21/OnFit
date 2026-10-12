import catalog from '../../backend/src/main/resources/company-groups.json' with { type: 'json' };

const legalForms = /\(\s*(?:주|유|사|재|株)\s*\)|㈜|주식회사|유한책임회사|유한회사/gu;
const companyName = value => value.replace(legalForms, ' ').replace(/\s+/gu, ' ').trim();
const key = value => companyName(value).replace(/\s+/gu, '').toLowerCase();
const groups = new Map(Object.entries(catalog.groups).map(([name, members]) => [key(name), members]));

export const splitCompanyInterests = value => value.split(/[,，\n]/u).map(companyName).filter(Boolean);
export function expandCompanies(interests) {
  const result = new Map();
  for (const interest of interests) {
    const name = companyName(interest);
    if (!name) continue;
    for (const member of groups.get(key(name)) || [name]) {
      if (!result.has(key(member))) result.set(key(member), member);
    }
  }
  return [...result.values()];
}
export function matchesCompany(company, interests) {
  return expandCompanies(interests).some(member => key(member) === key(company));
}
