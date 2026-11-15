import { Link } from "@tanstack/react-router";
import { BookText, FlaskConical } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * The dev backend's two icons beside the logo: the Dev page (demo reset, dev games) and the docs (served by the
 * backend at /docs/, which the dev server proxies, so they open on the app's own address in a new tab). Shown
 * only when the backend is a dev backend, signed in or not.
 */
export function DevLinks({ active }: { active: boolean }) {
  return (
    <span className="flex items-center">
      <Button asChild size="icon" variant="ghost" className={cn(active && "text-primary")}>
        <Link
          to="/dev"
          aria-label="Dev data"
          title="Dev data"
          aria-current={active ? "page" : undefined}
        >
          <FlaskConical />
        </Link>
      </Button>
      <Button asChild size="icon" variant="ghost">
        <a
          href="/docs/"
          target="_blank"
          rel="noopener"
          aria-label="Docs (opens in a new tab)"
          title="Docs"
        >
          <BookText />
        </a>
      </Button>
    </span>
  );
}
