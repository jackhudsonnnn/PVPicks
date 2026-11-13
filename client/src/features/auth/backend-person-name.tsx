import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { backendErrorMessage, BackendError, getProfile, type BackendUser } from "./backend-api";

export function PersonName({
  id,
  user,
  expire,
  queryKey,
}: {
  id: string;
  user: BackendUser;
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
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);

  if (query.isPending) return <>Loading profile…</>;
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
