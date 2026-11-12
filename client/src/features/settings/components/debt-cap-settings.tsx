import { useState } from "react";
import { ShieldCheck, Save } from "lucide-react";
import { SettingRow, SettingsSection } from "@/components/settings";
import { Switch } from "@/components/ui/switch";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useAppStore } from "@/features/store";
import { formatPts } from "@/features/bets/ledger";
import { DEFAULT_DEBT_CAP, isDebtCap, userDebt } from "../debt-cap";

export function DebtCapSettings() {
  const { state, actions } = useAppStore();
  const cap = state.debtCaps?.[state.meId] ?? DEFAULT_DEBT_CAP;
  const debt = userDebt(state, state.meId);
  const locked = debt > 0;
  const [draft, setDraft] = useState(String(cap ?? 0));
  const amount = draft.trim() === "" ? NaN : Number(draft);
  const valid = isDebtCap(amount);
  return (
    <SettingsSection title="Limits">
      <SettingRow
        icon={ShieldCheck}
        label="Debt Cap"
        htmlFor="debt-cap-on"
        description="Limit your debt across tables and settle-ups. 4fun bets are exempt."
        control={<Switch id="debt-cap-on" aria-label="Debt Cap" checked={cap !== null} disabled={locked}
          onCheckedChange={(on) => actions.setDebtCap(on ? (valid ? amount : 0) : null)} />}
      >
        <form className="flex items-end gap-2" onSubmit={(e) => {
          e.preventDefault();
          if (valid && cap !== null && !locked) actions.setDebtCap(amount);
        }}>
          <div className="min-w-0 flex-1">
            <label htmlFor="debt-cap-amount" className="mb-1 block text-xs text-muted-foreground">Whole-point limit</label>
            <Input id="debt-cap-amount" type="number" min={0} step={1} inputMode="numeric"
              disabled={locked || cap === null} value={draft} onChange={(e) => setDraft(e.target.value)} />
          </div>
          <Button type="submit" variant="outline" disabled={locked || cap === null || !valid || amount === cap}>
            <Save className="size-4" /> Save
          </Button>
        </form>
        <p className="mt-3 text-sm font-mono">Current debt: {formatPts(debt)}{cap !== null ? ` / ${formatPts(cap)}` : ""} pts</p>
        <p className="mt-1 text-xs text-muted-foreground">
          {locked ? "Clear all debt to change this limit or switch it on or off. Positive balances elsewhere don't cancel debt." : cap === null ? "Off — no debt limit." : "Picks that exceed your cap sit out when tickets lock."}
        </p>
      </SettingRow>
    </SettingsSection>
  );
}