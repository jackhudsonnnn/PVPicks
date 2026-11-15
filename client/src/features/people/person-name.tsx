import { useQuery } from "@tanstack/react-query";
import { backendErrorMessage } from "@/lib/api-client";
import { getProfile, type User } from "./api";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { LoadingText } from "@/components/loading";

export function PersonName({
  id,
  user,
  expire,
  queryKey,
}: {
  id: string;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
}) {
  if (id === user.id) return <>You</>;
  return <ProfileName id={id} expire={expire} queryKey={queryKey} />;
}

function ProfileName({
  id,
  expire,
  queryKey,
}: {
  id: string;
  expire: () => void;
  queryKey: readonly string[];
}) {
  const query = useQuery({
    queryKey: [...queryKey, "profile", id],
    queryFn: ({ signal }) => getProfile(id, signal),
    retry: false,
    // Live profile.changed events invalidate this, so it never needs to refetch on its own.
    staleTime: Infinity,
  });
  const unauthorized = useExpireOnUnauthorized([query.error], expire);

  if (query.isPending) return <LoadingText />;
  if (query.isError) {
    return (
      <span>
        <span role="alert">
          Profile {id}: {backendErrorMessage(query.error)}{" "}
        </span>
        {!unauthorized && (
          <button
            type="button"
            className="text-primary underline"
            onClick={() => void query.refetch()}
          >
            Retry profile
          </button>
        )}
      </span>
    );
  }
  return <>{"username" in query.data ? query.data.username : query.data.displayName}</>;
}
