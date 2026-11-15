/** Normalize known role aliases without removing meaningful punctuation (C++, C#, etc.). */
export function normalizeRole(text) {
  return text.normalize('NFKC').replace(/\s/g, '')
    .replace(/^s\/?w(?=$|[^a-z]|engineer|developer)/i, 'SW');
}

/** A comparison covers distinct roles in one reviewed posting. */
export function parseComparisonRoles(text) {
  const lines = text.split(/\r?\n/).map(normalizeRole).filter(Boolean);
  const keys = lines.map(line => line.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase());
  if (lines.length < 2 || lines.length > 5) return { roles: lines, error: '비교할 직무를 2개부터 5개까지 입력해 주세요.' };
  if (lines.some(line => line.length > 120)) return { roles: lines, error: '직무명은 각각 120자 이내로 입력해 주세요.' };
  if (new Set(keys).size !== keys.length) return { roles: lines, error: '같은 직무를 중복해 입력할 수 없어요.' };
  return { roles: lines, error: '' };
}

const points = (item, key) => Number(item.result?.score.rubric?.categories?.find(category => category.key === key)?.points ?? 0);

/** Required-condition failures stay outside recommendations; ties use the rubric's stated order. */
export function rankComparison(entries) {
  const eligible = entries.filter(item => item.result?.score.total != null && !item.result.score.noRequirements && item.result.score.rubric?.eligibility !== '미충족');
  eligible.sort((a, b) => Number(b.result.score.total) - Number(a.result.score.total)
    || points(b, 'work') - points(a, 'work')
    || points(b, 'technology') - points(a, 'technology')
    || points(b, 'userPreference') - points(a, 'userPreference'));
  const ranked = eligible.map((item, index) => {
    const previous = eligible[index - 1];
    const tied = previous && Number(previous.result.score.total) === Number(item.result.score.total)
      && ['work', 'technology', 'userPreference'].every(key => points(previous, key) === points(item, key));
    return { ...item, rank: tied ? null : index + 1,
      similarFit: !!previous && Math.abs(Number(previous.result.score.total) - Number(item.result.score.total)) <= 3,
      provisional: item.result.score.rubric?.evidenceLevel === '낮음' };
  });
  for (let i = 0; i < ranked.length; i++) if (ranked[i].rank == null) ranked[i].rank = ranked[i - 1].rank;
  return [...ranked, ...entries.filter(item => !eligible.includes(item)).map(item => ({ ...item, rank: null }))];
}

export async function compareRoles(roles, body, analyze, signal, onProgress = () => {}) {
  const entries = [];
  for (const role of roles) {
    if (signal.aborted) throw new DOMException('Canceled', 'AbortError');
    try {
      entries.push({ role, result: await analyze({ ...body, targetRole: role }, signal), error: '' });
    } catch (error) {
      if (signal.aborted || error.name === 'AbortError') throw error;
      entries.push({ role, result: null, error: error.message || '분석하지 못했습니다.' });
    }
    onProgress([...entries], roles.length);
  }
  return entries;
}
