import { useEffect } from "react";
import { toast } from "sonner";
import { Toaster } from "@/components/ui/sonner";
import { emitError, onError } from "@/lib/error-bus";

export function ErrorToasts({ admin = false }: { admin?: boolean }) {
  useEffect(() => {
    const unsubscribe = onError(({ title, description }) => toast.error(title, { description }));
    const onWindowError = (event: Event) => {
      if (admin && event instanceof ErrorEvent) emitError("Page error", event.message);
      else emitError("Something went wrong", "Try that again in a moment.");
    };
    window.addEventListener("error", onWindowError);
    window.addEventListener("unhandledrejection", onWindowError);
    return () => {
      unsubscribe();
      window.removeEventListener("error", onWindowError);
      window.removeEventListener("unhandledrejection", onWindowError);
    };
  }, [admin]);
  return <Toaster />;
}
