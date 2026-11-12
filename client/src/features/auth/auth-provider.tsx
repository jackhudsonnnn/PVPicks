import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import type { Profile } from "@/features/settings/profile";
import { isArray, readJson, removeKeys, writeJson } from "@/lib/storage";
import {
  RESERVED_USERNAMES,
  checkLogin,
  isAccountId,
  personaFor,
  type AccountId,
} from "./demo-accounts";
import {
  checkLocalLogin,
  createLocalAccount,
  localKey,
  personaOf,
  signUpErrors,
  type LocalAccount,
  type SignUpErrors,
  type SignUpInput,
} from "./local-accounts";
import { LEGACY_SHARED_KEYS, SHARED_KEYS } from "./storage-keys";

export interface Session {
  /** Storage namespace and attribution: a demo id ("pro", "admin") or "user:<username>". */
  key: string;
  /** Which sample data to load. Signed-up accounts start with the brand-new (newbie) world. */
  world: AccountId;
  /** Who you are in that world. */
  persona: Profile;
}

interface Auth {
  session: Session | null;
  /** The signed-in account's storage key (same as `session.key`). */
  account: string | null;
  /** False until the saved sign-in has been read in the browser. */
  ready: boolean;
  /** Resolves false when the username/password don't match any account. */
  logIn: (username: string, password: string) => Promise<boolean>;
  /** Creates the account and signs in; resolves with field errors if it couldn't. */
  signUp: (input: SignUpInput) => Promise<SignUpErrors>;
  /** Usernames nobody else can take (demo logins, signed-up accounts, sample people). */
  takenUsernames: string[];
  /** Optional message to show on the sign-in screen (e.g. after deleting an account). */
  signOut: (notice?: string) => void;
  /** Forget a signed-up account entirely (account deletion). Demo accounts just sign out. */
  forgetAccount: (notice?: string) => void;
  notice: string | null;
  clearNotice: () => void;
}

const AuthContext = createContext<Auth | null>(null);

// TODO(backend): accounts belong on a server; saved in this browser until then.
const readLocal = () => readJson<LocalAccount[]>(SHARED_KEYS.localAccounts, [], isArray);
const saveLocal = (accounts: LocalAccount[]) => writeJson(SHARED_KEYS.localAccounts, accounts);

function sessionFor(key: string, locals: LocalAccount[]): Session | null {
  if (isAccountId(key)) return { key, world: key, persona: personaFor(key) };
  const a = locals.find((x) => localKey(x.username) === key);
  return a ? localSession(a) : null;
}

const localSession = (a: LocalAccount): Session => ({
  key: localKey(a.username),
  world: "newbie",
  persona: personaOf(a),
});

const takenFrom = (locals: LocalAccount[]) => [
  ...RESERVED_USERNAMES,
  ...locals.map((a) => a.username),
];

function newSalt() {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

/** Sign-in for demo and signed-up accounts; remembers who is signed in on this browser. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [locals, setLocals] = useState<LocalAccount[]>([]);
  const [ready, setReady] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    // Profiles and inboxes used to be shared by every account; start everyone fresh.
    removeKeys(...LEGACY_SHARED_KEYS);
    const l = readLocal();
    setLocals(l);
    const saved = readJson<string | null>(SHARED_KEYS.session, null);
    if (typeof saved === "string") setSession(sessionFor(saved, l));
    setReady(true);
  }, []);

  const start = useCallback((s: Session) => {
    writeJson(SHARED_KEYS.session, s.key);
    setNotice(null);
    setSession(s);
  }, []);

  const logIn = useCallback(
    async (username: string, password: string) => {
      const demo = checkLogin(username, password);
      if (demo) {
        start(sessionFor(demo, locals)!);
        return true;
      }
      const a = await checkLocalLogin(locals, username, password);
      if (!a) return false;
      start(localSession(a));
      return true;
    },
    [locals, start],
  );

  const signUp = useCallback(
    async (input: SignUpInput) => {
      const errors = signUpErrors(input, takenFrom(readLocal()));
      if (Object.keys(errors).length) return errors;
      const a = await createLocalAccount(input, newSalt(), Date.now());
      // Re-read after hashing so an account made meanwhile (another tab) isn't lost.
      const current = readLocal();
      if (signUpErrors(input, takenFrom(current)).username)
        return { username: "Someone already has that username." };
      const next = [...current, a];
      saveLocal(next);
      setLocals(next);
      start(localSession(a));
      return {};
    },
    [start],
  );

  const signOut = useCallback((msg?: string) => {
    removeKeys(SHARED_KEYS.session);
    setNotice(msg ?? null);
    setSession(null);
  }, []);

  const forgetAccount = useCallback(
    (msg?: string) => {
      if (session?.key.startsWith("user:")) {
        const next = readLocal().filter((a) => localKey(a.username) !== session.key);
        saveLocal(next);
        setLocals(next);
      }
      signOut(msg);
    },
    [session, signOut],
  );

  const clearNotice = useCallback(() => setNotice(null), []);

  return (
    <AuthContext.Provider
      value={{
        session,
        account: session?.key ?? null,
        ready,
        logIn,
        signUp,
        takenUsernames: takenFrom(locals),
        signOut,
        forgetAccount,
        notice,
        clearNotice,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
