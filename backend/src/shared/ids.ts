import { createHash, randomBytes, randomUUID, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb) as (p: string, s: Buffer, k: number) => Promise<Buffer>;

/** Crockford-ish alphabet without ambiguous characters. */
const ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';

/** Generates an Ara ID like ARA-7KQ2-MZW4-TX93. */
export function generateAraId(): string {
  const bytes = randomBytes(12);
  const block = (offset: number) =>
    Array.from(bytes.subarray(offset, offset + 4))
      .map((b) => ALPHABET[b % ALPHABET.length])
      .join('');
  return `ARA-${block(0)}-${block(4)}-${block(8)}`;
}

export function isValidAraId(id: string): boolean {
  return /^ARA-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(id);
}

export function sha256Hex(input: string): string {
  return createHash('sha256').update(input).digest('hex');
}

export function sha256B64(input: string): string {
  return createHash('sha256').update(input).digest('base64');
}

/** Deterministic phone hash — the raw number is never stored. */
export function hashPhone(phone: string): string {
  return sha256Hex(phone.trim());
}

/** OTP + refresh token secrets. */
export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

export function randomOtpCode(): string {
  return (randomBytes(4).readUInt32BE(0) % 1_000_000).toString().padStart(6, '0');
}

/** scrypt hash for OTP codes at rest (rate-limited path, still never plaintext). */
export async function hashCode(code: string, salt: Buffer): Promise<string> {
  const derived = await scrypt(code, salt, 32);
  return derived.toString('base64');
}

export async function verifyCode(code: string, salt: Buffer, expected: string): Promise<boolean> {
  const derived = await scrypt(code, salt, 32);
  const expectedBuf = Buffer.from(expected, 'base64');
  return derived.length === expectedBuf.length && timingSafeEqual(derived, expectedBuf);
}

export function uuid(): string {
  return randomUUID();
}
