import { Toaster as Sonner } from "sonner";

type ToasterProps = React.ComponentProps<typeof Sonner>;

const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      className="toaster group"
      theme="dark"
      // Above the phone tab bar, never over it (styles.css sets --toast-bottom per breakpoint). Sonner switches
      // to its mobile layout below 600px, so both offsets point at the same variable.
      offset={{ bottom: "var(--toast-bottom)" }}
      mobileOffset={{ bottom: "var(--toast-bottom)" }}
      // Long enough to read a sentence; the x closes one sooner.
      duration={7000}
      closeButton
      toastOptions={{
        classNames: {
          // Sonner puts the x on the top-left corner; ours sits top-right.
          closeButton:
            "group-[.toast]:!left-auto group-[.toast]:!right-0 group-[.toast]:!translate-x-[35%] group-[.toast]:!-translate-y-[35%] group-[.toast]:!border-border group-[.toast]:!bg-surface-raised group-[.toast]:!text-muted-foreground hover:group-[.toast]:!text-foreground",
          toast:
            "group toast group-[.toaster]:bg-surface group-[.toaster]:text-foreground group-[.toaster]:border-border group-[.toaster]:shadow-lg group-[.toaster]:font-sans",
          title: "group-[.toast]:font-semibold",
          error:
            "group-[.toaster]:border-loss/70 group-[.toaster]:text-foreground [&_[data-icon]]:text-loss",
          success: "group-[.toaster]:border-win/70 [&_[data-icon]]:text-win",
          description: "group-[.toast]:text-muted-foreground group-[.toast]:line-clamp-2",
          actionButton: "group-[.toast]:bg-primary group-[.toast]:text-primary-foreground",
          cancelButton: "group-[.toast]:bg-muted group-[.toast]:text-muted-foreground",
        },
      }}
      {...props}
    />
  );
};

export { Toaster };
