import { z } from "zod";
import { recordApiCall } from "./perf";

/** Every API id is a UUID; checking first turns a malformed route parameter into a 404 without a request. */
export const idSchema = z.guid();

export class BackendError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
    /** The server's explanation of a 403 or 409, for example "A table must retain an active host." */
    readonly detail?: string,
    /** The whole 403/409 body, for errors that carry more than a code (like deletion blockers). */
    readonly body?: unknown,
  ) {
    super(message);
    this.name = "BackendError";
  }
}

/** A 401 means the server no longer recognizes the session. */
export function isUnauthorized(error: unknown): error is BackendError {
  return error instanceof BackendError && error.status === 401;
}

export async function request(path: string, init?: RequestInit): Promise<Response> {
  const started = performance.now();
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      credentials: "same-origin",
      cache: "no-store",
    });
  } catch (error) {
    // An aborted request (a query cancelled on navigation) isn't a failure worth reporting.
    if (!(error instanceof DOMException && error.name === "AbortError"))
      recordApiCall(init?.method ?? "GET", path, 0, performance.now() - started);
    throw error;
  }
  recordApiCall(init?.method ?? "GET", path, response.status, performance.now() - started);
  if (!response.ok) {
    let code: string | undefined;
    let detail: string | undefined;
    let body: unknown;
    // 400, 403, 409 and 429 carry a code saying why (a weak password, a frozen account, conflicts, limits).
    if ([400, 403, 409, 429].includes(response.status)) {
      try {
        body = await response.json();
        const parsed = z
          .object({ code: z.string(), message: z.string().optional() })
          .safeParse(body);
        if (parsed.success) {
          code = parsed.data.code;
          detail = parsed.data.message;
        }
      } catch (error) {
        if (!(error instanceof SyntaxError)) throw error;
      }
    }
    throw new BackendError("Backend request failed.", response.status, code, detail, body);
  }
  return response;
}

export async function read<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  let value: unknown;
  try {
    value = await response.json();
  } catch (error) {
    if (error instanceof SyntaxError) throw new BackendError("The backend returned invalid JSON.");
    throw error;
  }
  const result = schema.safeParse(value);
  if (!result.success) throw new BackendError("The backend returned an unexpected response.");
  return result.data;
}

export async function csrf(): Promise<string> {
  const response = await request("/auth/csrf");
  return (await read(response, z.object({ token: z.string().min(1) }))).token;
}

export async function mutationRequest(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  key: string,
  body?: object,
): Promise<Response> {
  if (!key || key.length > 255) throw new BackendError("Invalid retry key.");
  const token = await csrf();
  return request(path, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      "X-CSRF-TOKEN": token,
      "Idempotency-Key": key,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

export function backendErrorMessage(error: unknown): string {
  if (error instanceof BackendError) {
    // Slurs are refused anywhere you write (chat, polls, bets, names): say so plainly.
    if (error.code === "abusive_language" && error.detail) return error.detail;
    if (error.status === 403)
      return "This account cannot access that action, or the request was rejected.";
    if (error.status === 404) return "This item is unavailable or you no longer have access.";
    if (error.status === 409 && error.detail) return error.detail;
    if (error.status !== undefined) return `The backend returned an error (${error.status}).`;
    return error.message;
  }
  if (error instanceof TypeError)
    return "Could not reach the backend. Check that the dev server is running.";
  return "An unexpected error occurred. Please try again.";
}
