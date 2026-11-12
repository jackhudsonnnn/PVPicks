/** Points read like dollars and are always kept to the nearest cent. */
export const toCents = (n: number) => Math.round(n * 100) / 100;
