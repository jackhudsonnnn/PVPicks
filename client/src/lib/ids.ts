let seq = 0;

/**
 * A new id that is unique in this browser session (time + counter), e.g. `b_m3k2x1a0`.
 * Ids made on the client are temporary; a server will hand out its own once one exists.
 */
export function newId(prefix: string) {
  return `${prefix}_${Date.now().toString(36)}${(seq++).toString(36)}`;
}
