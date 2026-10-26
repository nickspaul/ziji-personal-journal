// Drafts are kept only in this open app session, never written to browser storage.
export const recordDrafts:Record<string,any>={}
export function clearDrafts(){for(const key of Object.keys(recordDrafts))delete recordDrafts[key]}
