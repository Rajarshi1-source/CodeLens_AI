import { describe, expect, it } from 'vitest';
import {
  PresenceSnapshotSchema,
  PullRequestSchema,
  ReviewStatusMessageSchema,
  ReviewTopicMessageSchema,
} from './schemas';

// Payload shapes exactly as the backend serializes them (see ReviewTopicMessage / PullRequestDto).
describe('wire schemas', () => {
  it('parses each member of the review-topic union by its type', () => {
    const token = ReviewTopicMessageSchema.parse({
      type: 'token',
      sessionId: 9,
      seq: 3,
      chunk: 0,
      key: 'ab12',
      file: 'src/A.java',
      line: 12,
      severity: 'CRITICAL',
      text: 'Hard-coded secret',
      confidence: 0.9,
      ruleKey: 'local.hardcoded-secret',
    });
    expect(token.type).toBe('token');
    expect(ReviewTopicMessageSchema.parse({ type: 'reset', sessionId: 9, seq: 4, chunk: 0 }).type).toBe('reset');
    const err = ReviewTopicMessageSchema.parse({ type: 'error', sessionId: 9, seq: 5, chunk: 1, file: null });
    expect(err.type === 'error' && err.file).toBe('');
  });

  it('rejects an unknown topic message type', () => {
    expect(ReviewTopicMessageSchema.safeParse({ type: 'bogus', sessionId: 1, seq: 1 }).success).toBe(false);
  });

  it('parses a status message with a failure reason', () => {
    expect(
      ReviewStatusMessageSchema.parse({ sessionId: 2, status: 'REVIEW_UNAVAILABLE', reason: 'BUDGET_EXCEEDED' }).reason,
    ).toBe('BUDGET_EXCEEDED');
  });

  it('accepts an unscored PR (null risk)', () => {
    const pr = PullRequestSchema.parse({
      id: 1,
      number: 5,
      title: 't',
      author: 'a',
      status: 'PENDING',
      filesChanged: 1,
      additions: 1,
      deletions: 0,
      htmlUrl: null,
      headSha: null,
      riskLevel: null,
      riskScore: null,
      latestSessionId: null,
    });
    expect(pr.riskLevel).toBeNull();
  });

  it('parses a presence snapshot', () => {
    const snap = PresenceSnapshotSchema.parse({
      prId: 3,
      viewers: [
        { sessionId: 's1', userId: -1002, login: 'demo-bob', avatarUrl: null, file: 'a.ts', line: 4, typing: false, lastSeen: 1 },
      ],
    });
    expect(snap.viewers[0].login).toBe('demo-bob');
  });
});
