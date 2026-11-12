import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  Outlet,
  Link,
  createRootRouteWithContext,
  useRouter,
  useRouterState,
  HeadContent,
  Scripts,
  type ErrorComponentProps,
} from "@tanstack/react-router";
import { useEffect, type ReactNode } from "react";

import appCss from "../styles.css?url";
import { reportLovableError } from "../lib/lovable-error-reporting";
import { AppStoreProvider } from "@/features/store";
import { SettingsProvider } from "@/features/settings/settings-provider";
import { AppShell } from "@/components/app-shell";
import { Toaster } from "@/components/ui/sonner";
import { NotificationsProvider } from "@/features/notifications/notifications";
import { AuthProvider, useAuth } from "@/features/auth/auth-provider";
import { SignInScreen } from "@/features/auth/sign-in-screen";
import { SignUpScreen } from "@/features/auth/sign-up-screen";
import { AdminShell } from "@/features/admin/components/admin-shell";

function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-7xl font-black text-foreground">404</h1>
        <h2 className="mt-4 text-xl font-semibold text-foreground">Page not found</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div className="mt-6">
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Go home
          </Link>
        </div>
      </div>
    </div>
  );
}

function ErrorComponent({ error, reset }: ErrorComponentProps) {
  console.error(error);
  const router = useRouter();
  useEffect(() => {
    reportLovableError(error, { boundary: "tanstack_root_error_component" });
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">
          This page didn't load
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Something went wrong on our end. You can try refreshing or head back home.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <button
            onClick={() => {
              router.invalidate();
              reset();
            }}
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Try again
          </button>
          <a
            href="/"
            className="inline-flex items-center justify-center rounded-md border border-input bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            Go home
          </a>
        </div>
      </div>
    </div>
  );
}

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "P2Picks | Bet with friends, not the house" },
      {
        name: "description",
        content: "Peer-to-peer NFL and NBA picks with your friends. No house, no vig.",
      },
      { name: "author", content: "Jack Hudson" },
      { property: "og:title", content: "P2Picks | Bet with friends, not the house" },
      {
        property: "og:description",
        content: "Peer-to-peer NFL and NBA picks with your friends. No house, no vig.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
      { name: "theme-color", content: "#121212" },
    ],
    links: [
      { rel: "preconnect", href: "https://fonts.googleapis.com" },
      { rel: "preconnect", href: "https://fonts.gstatic.com", crossOrigin: "anonymous" },
      {
        rel: "stylesheet",
        href: "https://fonts.googleapis.com/css2?family=Big+Shoulders+Display:wght@600;700;800;900&family=JetBrains+Mono:wght@400;700&family=Manrope:wght@400;500;600;700&display=swap",
      },
      { rel: "stylesheet", href: appCss },
      { rel: "icon", href: "/favicon.ico", type: "image/x-icon" },
    ],
  }),
  shellComponent: RootShell,
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootShell({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

function RootComponent() {
  const { queryClient } = Route.useRouteContext();

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <SignedInApp />
      </AuthProvider>
    </QueryClientProvider>
  );
}

/** Sign-in gate: the app loads the signed-in account's sample data as that account's person. */
function SignedInApp() {
  const { session, ready } = useAuth();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  if (!ready) return <div className="min-h-dvh bg-background" />;
  if (!session) return pathname === "/sign-up" ? <SignUpScreen /> : <SignInScreen />;
  if (session.world === "admin") return <AdminShell />;
  return (
    <AppStoreProvider
      key={session.key}
      account={session.world}
      me={session.persona}
      storageKey={session.key}
    >
      <SettingsProvider>
        <NotificationsProvider>
          <AppShell>
            <Outlet />
          </AppShell>
        </NotificationsProvider>
      </SettingsProvider>
    </AppStoreProvider>
  );
}
