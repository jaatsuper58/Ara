import pino from 'pino';

/**
 * Structured logging with hard redaction of protocol payloads.
 * SECURITY (§9): no message plaintext or ciphertext is ever logged —
 * the redact list strips envelope payload fields at the logger level so a
 * careless log statement cannot leak them either.
 */
export const logger = pino({
  level: process.env.LOG_LEVEL ?? 'info',
  redact: {
    paths: [
      'payload',
      'envelope',
      'envelope.payload',
      'ciphertext',
      'nonce',
      '*.ciphertext',
      '*.nonce',
      'req.headers.authorization',
      'refreshToken',
      'refresh_token',
      'code',
    ],
    censor: '[redacted]',
  },
  base: { service: process.env.ARA_SERVICE ?? 'ara' },
});
