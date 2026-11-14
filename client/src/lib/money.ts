/**
 * Points read like dollars and travel as whole cents (safe integers from the API, bigint for 64-bit totals).
 * "1.50", "-4.00", and with `signed` "+1.50"; zero is never signed.
 */
export function formatCents(cents: number | bigint, signed = false) {
  const value = BigInt(cents);
  const abs = value < 0n ? -value : value;
  const sign = value < 0n ? "-" : signed && value > 0n ? "+" : "";
  return `${sign}${(abs / 100n).toLocaleString()}.${(abs % 100n).toString().padStart(2, "0")}`;
}
