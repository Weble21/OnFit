/** A comparison covers distinct roles in one reviewed posting. */
export function parseComparisonRoles(text) {
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const keys = lines.map(line => line.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase());
  if (lines.length < 2 || lines.length > 5) return { roles: lines, error: '비교할 직무를 2개부터 5개까지 입력해 주세요.' };
  if (lines.some(line => line.length > 120)) return { roles: lines, error: '직무명은 각각 120자 이내로 입력해 주세요.' };
  if (new Set(keys).size !== keys.length) return { roles: lines, error: '같은 직무를 중복해 입력할 수 없어요.' };
  return { roles: lines, error: '' };
}

/** Unscored and failed analyses never receive a rank. Equal scores share a rank. */
export function rankComparison(entries) {
  const scored = entries.filter(item => item.result && item.result.score.total != null && !item.result.score.noRequirements)
    .sort((a, b) => Number(b.result.score.total) - Number(a.result.score.total));
  return [...scored.map((item, index) => ({ ...item, rank: index && Number(item.result.score.total) === Number(scored[index - 1].result.score.total) ? null : index + 1 }))
    .map((item, index, items) => ({ ...item, rank: item.rank ?? items.slice(0, index).reverse().find(previous => previous.rank != null)?.rank ?? 1 })),
    ...entries.filter(item => !item.result || item.result.score.total == null || item.result.score.noRequirements).map(item => ({ ...item, rank: null }))];
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
