import jwt from 'jsonwebtoken';
import type { Config } from './config.js';

export interface SessionClaims {
  sub: string; // Ara ID
  device: string;
}

export function signAccessToken(claims: SessionClaims, config: Config): string {
  return jwt.sign({ device: claims.device }, config.jwtSecret, {
    subject: claims.sub,
    expiresIn: config.accessTokenTtlSeconds,
  });
}

export function verifyAccessToken(token: string, config: Config): SessionClaims | null {
  try {
    const decoded = jwt.verify(token, config.jwtSecret) as jwt.JwtPayload;
    if (!decoded.sub) return null;
    return { sub: decoded.sub, device: String(decoded.device ?? '') };
  } catch {
    return null;
  }
}
