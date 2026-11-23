import type { ReactNode } from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SettingsProvider } from "@/features/preferences/settings-provider";

/**
 * Renders `ui` with a fresh query client that doesn't retry (so a failed request shows at once), and optionally the
 * app's settings. Returns the client too, for tests that invalidate or seed queries.
 */
export function renderWithQuery(ui: ReactNode, { settings = false }: { settings?: boolean } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(
    <QueryClientProvider client={client}>
      {settings ? <SettingsProvider>{ui}</SettingsProvider> : ui}
    </QueryClientProvider>,
  );
  return { client, ...result };
}
