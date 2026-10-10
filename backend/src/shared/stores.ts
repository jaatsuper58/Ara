/**
 * Data-access interface + in-memory implementation.
 *
 * PRIVACY: the store schema deliberately has NO message-content type. The only
 * payload-shaped type is OfflineEnvelope, which holds opaque encrypted bytes
 * queued for offline devices and purged on delivery or TTL.
 */

export interface PublicKeyBundle {
  identityX25519: string; // base64
  identityEd25519: string; // base64
  signedPreKeyId: number;
  signedPreKey: string; // base64
  signedPreKeySignature: string; // base64
}

export interface User {
  id: string;
  createdAt: number;
  recoveryEmail: string | null;
  phoneHash: string | null;
  publicKeyBundle: PublicKeyBundle;
  lastSeen: number | null;
  statusVisibility: boolean;
}

export interface Device {
  userId: string;
  id: string;
  pushToken: string | null;
  platform: string;
  publicKey: string | null;
}

export interface OneTimePreKey {
  userId: string;
  keyId: number;
  publicKey: string;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
  joinedAt: number;
  muted: boolean;
}

export interface Group {
  id: string;
  name: string;
  photoUrl: string | null;
  description: string | null;
  ownerId: string;
  createdAt: number;
  memberLimit: number;
}

/** Opaque encrypted envelope queued for an offline user (TTL-bounded). */
export interface OfflineEnvelope {
  id: string;
  recipient: string;
  /** Serialized envelope — opaque to the server; never parsed or logged. */
  blob: string;
  queuedAt: number;
}

export interface UserStore {
  // users
  createUser(user: User): Promise<void>;
  getUser(araId: string): Promise<User | null>;
  getUserByPhoneHash(phoneHash: string): Promise<User | null>;
  updateUser(araId: string, patch: Partial<Pick<User, 'recoveryEmail' | 'phoneHash' | 'statusVisibility' | 'lastSeen'>>): Promise<void>;
  araIdTaken(araId: string): Promise<boolean>;

  // prekeys
  setPublicKeyBundle(araId: string, bundle: PublicKeyBundle): Promise<void>;
  popOneTimePreKey(araId: string): Promise<OneTimePreKey | null>;
  addOneTimePreKeys(keys: OneTimePreKey[]): Promise<number>;
  oneTimePreKeyCount(araId: string): Promise<number>;

  // devices
  upsertDevice(device: Device): Promise<void>;
  getDevices(userId: string): Promise<Device[]>;
  setPushToken(userId: string, deviceId: string, token: string | null): Promise<void>;

  // sessions
  createSession(s: { userId: string; deviceId: string; refreshTokenHash: string; expiresAt: number }): Promise<void>;
  findSessionByRefreshHash(hash: string): Promise<{ userId: string; deviceId: string; expiresAt: number } | null>;
  deleteSessionsByRefreshHash(hash: string): Promise<void>;

  // groups
  createGroup(group: Group, members: GroupMember[]): Promise<void>;
  getGroup(id: string): Promise<{ group: Group; members: GroupMember[] } | null>;
  patchGroup(id: string, patch: Partial<Pick<Group, 'name' | 'photoUrl' | 'description'>>): Promise<Group | null>;
  addMembers(groupId: string, members: GroupMember[]): Promise<void>;
  removeMember(groupId: string, userId: string): Promise<boolean>;

  // blocked users
  blockUser(blocker: string, blocked: string): Promise<void>;
  isBlocked(blocker: string, blocked: string): Promise<boolean>;

  // offline envelope queue (encrypted, opaque, TTL-bounded)
  queueEnvelope(envelope: OfflineEnvelope): Promise<void>;
  drainEnvelopes(recipient: string): Promise<OfflineEnvelope[]>;
}

export function createMemoryStore(): UserStore {
  const users = new Map<string, User>();
  const devices = new Map<string, Device>(); // key `${userId}:${deviceId}`
  const sessions = new Map<string, { userId: string; deviceId: string; expiresAt: number }>();
  const opks = new Map<string, OneTimePreKey[]>(); // userId -> keys
  const groups = new Map<string, Group>();
  const groupMembers = new Map<string, GroupMember[]>(); // groupId -> members
  const blocked = new Set<string>();
  const offlineQueue = new Map<string, OfflineEnvelope[]>();

  const deviceKey = (userId: string, id: string) => `${userId}:${id}`;

  return {
    async createUser(user) {
      users.set(user.id, user);
      opks.set(user.id, []);
      offlineQueue.set(user.id, []);
    },
    async getUser(araId) {
      return users.get(araId) ?? null;
    },
    async getUserByPhoneHash(phoneHash) {
      for (const u of users.values()) if (u.phoneHash === phoneHash) return u;
      return null;
    },
    async updateUser(araId, patch) {
      const u = users.get(araId);
      if (u) Object.assign(u, patch);
    },
    async araIdTaken(araId) {
      return users.has(araId);
    },

    async setPublicKeyBundle(araId, bundle) {
      const u = users.get(araId);
      if (u) u.publicKeyBundle = bundle;
    },
    async popOneTimePreKey(araId) {
      const list = opks.get(araId);
      if (!list) return null;
      // Prefer an unused key; keys are single-use (X3DH).
      return list.shift() ?? null;
    },
    async addOneTimePreKeys(keys) {
      let added = 0;
      for (const key of keys) {
        const list = opks.get(key.userId) ?? [];
        if (!list.some((k) => k.keyId === key.keyId)) {
          list.push(key);
          opks.set(key.userId, list);
          added++;
        }
      }
      return added;
    },
    async oneTimePreKeyCount(araId) {
      return opks.get(araId)?.length ?? 0;
    },

    async upsertDevice(device) {
      devices.set(deviceKey(device.userId, device.id), device);
    },
    async getDevices(userId) {
      return [...devices.values()].filter((d) => d.userId === userId);
    },
    async setPushToken(userId, deviceId, token) {
      const d = devices.get(deviceKey(userId, deviceId));
      if (d) d.pushToken = token;
    },

    async createSession(s) {
      sessions.set(s.refreshTokenHash, s);
    },
    async findSessionByRefreshHash(hash) {
      const s = sessions.get(hash);
      if (!s) return null;
      if (s.expiresAt < Date.now()) {
        sessions.delete(hash);
        return null;
      }
      return s;
    },
    async deleteSessionsByRefreshHash(hash) {
      sessions.delete(hash);
    },

    async createGroup(group, members) {
      groups.set(group.id, group);
      groupMembers.set(group.id, members);
    },
    async getGroup(id) {
      const g = groups.get(id);
      if (!g) return null;
      return { group: g, members: groupMembers.get(id) ?? [] };
    },
    async patchGroup(id, patch) {
      const g = groups.get(id);
      if (!g) return null;
      Object.assign(g, patch);
      return g;
    },
    async addMembers(groupId, members) {
      const list = groupMembers.get(groupId) ?? [];
      for (const m of members) {
        if (!list.some((x) => x.userId === m.userId)) list.push(m);
      }
      groupMembers.set(groupId, list);
    },
    async removeMember(groupId, userId) {
      const list = groupMembers.get(groupId) ?? [];
      const next = list.filter((m) => m.userId !== userId);
      groupMembers.set(groupId, next);
      return next.length !== list.length;
    },

    async blockUser(blocker, blockedId) {
      blocked.add(`${blocker}->${blockedId}`);
    },
    async isBlocked(blocker, blockedId) {
      return blocked.has(`${blocker}->${blockedId}`);
    },

    async queueEnvelope(envelope) {
      const list = offlineQueue.get(envelope.recipient) ?? [];
      list.push(envelope);
      // FIFO cap: drop oldest beyond the limit.
      if (list.length > 100) list.splice(0, list.length - 100);
      offlineQueue.set(envelope.recipient, list);
    },
    async drainEnvelopes(recipient) {
      const list = offlineQueue.get(recipient) ?? [];
      offlineQueue.set(recipient, []);
      const cutoff = Date.now() - 7 * 86_400_000;
      return list.filter((e) => e.queuedAt >= cutoff);
    },
  };
}
