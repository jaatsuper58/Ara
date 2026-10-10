/** Environment-driven configuration with dev-safe defaults. */
export interface Config {
  port: number;
  jwtSecret: string;
  accessTokenTtlSeconds: number;
  refreshTokenTtlSeconds: number;
  redisUrl: string | null;
  databaseUrl: string | null;
  otpDebug: boolean;
  maxFileChunkBytes: number;
  maxFileSizeBytes: number;
  fileTtlDays: number;
  offlineQueueLimit: number;
}

export function loadConfig(overrides: Partial<Config> = {}): Config {
  return {
    port: Number(process.env.PORT ?? 8080),
    // Dev-only default; production MUST set JWT_SECRET (see k8s/README).
    jwtSecret: process.env.JWT_SECRET ?? 'dev-insecure-secret-change-me',
    accessTokenTtlSeconds: Number(process.env.ACCESS_TTL ?? 900),
    refreshTokenTtlSeconds: Number(process.env.REFRESH_TTL ?? 60 * 60 * 24 * 30),
    redisUrl: process.env.REDIS_URL ?? null,
    databaseUrl: process.env.DATABASE_URL ?? null,
    otpDebug: process.env.OTP_DEBUG === '1',
    maxFileChunkBytes: 4 * 1024 * 1024,
    maxFileSizeBytes: Number(process.env.MAX_FILE_SIZE ?? 2 * 1024 * 1024 * 1024),
    fileTtlDays: 7,
    offlineQueueLimit: 100,
    ...overrides,
  };
}
