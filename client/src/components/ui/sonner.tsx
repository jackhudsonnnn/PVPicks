import { Toaster as Sonner } from "sonner";

type ToasterProps = React.ComponentProps<typeof Sonner>;

const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      className="toaster group"
      theme="dark"
      toastOptions={{
        classNames: {
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
