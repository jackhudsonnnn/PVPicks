import { SunMoon, Clock, Sparkles, Type } from "lucide-react";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { SettingRow, SettingsSection } from "@/components/settings";
import { Switch } from "@/components/ui/switch";
import type { ThemePref, MotionPref, TextSize } from "../display";
import { useSettings } from "../settings-provider";

export function DisplaySettings() {
  const { display, updateDisplay } = useSettings();
  return (
    <div className="space-y-4">
      <SettingsSection title="Display" description="Saved on this device.">
        <SettingRow icon={SunMoon} label="Theme" description="Light or dark colors.">
          <SegmentedTabs<ThemePref>
            size="sm"
            ariaLabel="Theme"
            tabs={[
              { id: "dark", label: "Dark" },
              { id: "light", label: "Light" },
              { id: "device", label: "Match device" },
            ]}
            value={display.theme}
            onChange={(v) => updateDisplay({ theme: v })}
          />
        </SettingRow>
        <SettingRow icon={Type} label="Text size" description="Scales the whole app.">
          <SegmentedTabs<TextSize>
            size="sm"
            ariaLabel="Text size"
            tabs={[
              { id: "small", label: "Small" },
              { id: "default", label: "Default" },
              { id: "large", label: "Large" },
            ]}
            value={display.textSize}
            onChange={(v) => updateDisplay({ textSize: v })}
          />
        </SettingRow>
        <SettingRow
          icon={Sparkles}
          label="Animations"
          description="Card swaps and players joining or leaving seats."
        >
          <SegmentedTabs<MotionPref>
            size="sm"
            ariaLabel="Animations"
            tabs={[
              { id: "device", label: "Match device" },
              { id: "reduced", label: "Reduced" },
              { id: "full", label: "Full" },
            ]}
            value={display.motion}
            onChange={(v) => updateDisplay({ motion: v })}
          />
        </SettingRow>
        <SettingRow
          icon={Clock}
          htmlFor="d-clock"
          label="24-hour clock"
          description="For new messages and tickets."
          control={
            <Switch
              id="d-clock"
              checked={display.clock24h}
              onCheckedChange={(v) => updateDisplay({ clock24h: v })}
            />
          }
        />
      </SettingsSection>
    </div>
  );
}
