/** People: user and profile reads, names, avatars and hue colors used across the app. Other code imports this feature only from here. */
export { userSchema } from "./api";
export type { User } from "./api";
export { AvatarStack, PersonAvatar } from "./avatar";
export { HUE_BG } from "./hue";
export { PersonName } from "./person-name";
export { usePerson } from "./use-person";
