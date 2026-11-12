import { createFileRoute, Link } from "@tanstack/react-router";
import { ShieldAlert } from "lucide-react";
import { useAuth } from "@/features/auth/auth-provider";
import { AdminDashboard } from "@/features/admin/components/admin-dashboard";

export const Route = createFileRoute("/admin")({
  head: () => ({
    meta: [
      { title: "Admin dashboard | P2Picks" },
      {
        name: "description",
        content: "Live P2Picks analytics across sample players and the reports review queue.",
      },
      { property: "og:title", content: "Admin dashboard | P2Picks" },
      {
        property: "og:description",
        content: "Live P2Picks analytics across sample players and the reports review queue.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
      { name: "robots", content: "noindex" },
    ],
  }),
  component: AdminPage,
});

function AdminPage() {
  const { account } = useAuth();
  if (account !== "admin")
    return (
      <div className="mx-auto max-w-md space-y-3 px-4 py-16 text-center">
        <ShieldAlert className="mx-auto size-10 text-muted-foreground" />
        <h1 className="text-4xl font-black uppercase">Admins only</h1>
        <p className="text-sm text-muted-foreground">
          Sign out and sign in with the admin demo account to see the dashboard.
        </p>
        <Link to="/" className="text-sm font-semibold text-primary underline">
          Back home
        </Link>
      </div>
    );
  return <AdminDashboard />;
}
