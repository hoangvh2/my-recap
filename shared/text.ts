/** Lowercase, no diacritics, đ → d: "Công ty Đông Á" matches "cong ty dong a". */
export function fold(s: string): string {
  return s.normalize("NFD").replace(/\p{M}/gu, "").replace(/đ/gi, "d").toLowerCase().replace(/\s+/g, " ").trim();
}

export interface Named {
  id: string;
  name: string;
}

/**
 * Finds the customer a spoken or typed name refers to. An exact match wins; otherwise a name that
 * contains, or is contained in, exactly one customer's name ("ABC" → "Công ty ABC"). Two or more
 * candidates mean the name is ambiguous, and nothing is returned rather than guessing.
 */
export function matchByName<T extends Named>(name: string, list: readonly T[]): T | null {
  const q = fold(name);
  if (q.length < 2) return null;
  const exact = list.filter((c) => fold(c.name) === q);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) return null;
  if (q.length < 3) return null;
  const partial = list.filter((c) => {
    const f = fold(c.name);
    return f.length >= 3 && (f.includes(q) || q.includes(f));
  });
  return partial.length === 1 ? partial[0] : null;
}
