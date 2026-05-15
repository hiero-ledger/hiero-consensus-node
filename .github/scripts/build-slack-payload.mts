// SPDX-License-Identifier: Apache-2.0
import { writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

interface TextField {
  type: 'plain_text' | 'mrkdwn';
  text: string;
  emoji?: boolean;
}

interface Block {
  type: string;
  text?: TextField;
  fields?: TextField[];
}

interface SlackMember {
  name: string;
  profile?: { email?: string };
}

interface SlackUsersListResponse {
  ok: boolean;
  members?: SlackMember[];
  response_metadata?: { next_cursor?: string };
}

async function findSlackUsername(email: string, token: string): Promise<string | null> {
  const normalizedEmail = email.toLowerCase();
  let cursor: string | undefined;

  do {
    const params = new URLSearchParams({ limit: '200' });
    if (cursor) params.set('cursor', cursor);

    const res = await fetch(`https://slack.com/api/users.list?${params}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const data = await res.json() as SlackUsersListResponse;

    if (!data.ok || !data.members) break;

    const match = data.members.find(
      m => m.profile?.email?.toLowerCase() === normalizedEmail,
    );
    if (match) return match.name;

    cursor = data.response_metadata?.next_cursor || undefined;
  } while (cursor);

  return null;
}

function git(format: string): string {
  return execSync(`git log -1 --pretty=format:${format}`).toString().trim();
}

const headerEmoji = process.env.HEADER_EMOJI ?? ':vertical_traffic_light:';
const headerText = process.env.HEADER_TEXT ?? '';
const result = process.env.RESULT ?? '';
const resultLabel = process.env.RESULT_LABEL ?? '';
const slackApiToken = process.env.SLACK_API_TOKEN ?? '';

const serverUrl = process.env.GITHUB_SERVER_URL ?? '';
const repository = process.env.GITHUB_REPOSITORY ?? '';
const runId = process.env.GITHUB_RUN_ID ?? '';
const ref = process.env.NOTIFY_REF ?? '';

const commitUrl = ref ? `${serverUrl}/${repository}/commit/${ref}` : '';
const runUrl = `${serverUrl}/${repository}/actions/runs/${runId}`;

const authorEmail = git('%ae');
const authorName = git('%an');

function colorFor(result: string): string {
  if (result === 'success') return '#00FF00';
  if (result === 'cancelled') return '#555555';
  return '#FF0000';
}

const blocks: Block[] = [
  { type: 'header', text: { type: 'plain_text', text: `${headerEmoji} ${headerText}`, emoji: true } },
  { type: 'divider' },
];

if (result && resultLabel) {
  blocks.push(
    { type: 'section', fields: [
      { type: 'plain_text', text: resultLabel },
      { type: 'plain_text', text: result },
    ] },
    { type: 'divider' },
  );
}

const infoFields: TextField[] = [];

if (commitUrl) {
  infoFields.push(
    { type: 'mrkdwn', text: '*Source Commit*:' },
    { type: 'mrkdwn', text: `<${commitUrl}>` },
  );
}
if (runId) {
  infoFields.push(
    { type: 'mrkdwn', text: '*Workflow run ID*:' },
    { type: 'mrkdwn', text: runId },
  );
}
if (runUrl) {
  infoFields.push(
    { type: 'mrkdwn', text: '*Workflow run URL*:' },
    { type: 'mrkdwn', text: `<${runUrl}>` },
  );
}

infoFields.push(
  { type: 'mrkdwn', text: '*Commit Author*:' },
  { type: 'mrkdwn', text: `${authorName} <${authorEmail}>` },
);

if (slackApiToken) {
  const username = await findSlackUsername(authorEmail, slackApiToken);
  infoFields.push(
    { type: 'mrkdwn', text: '*Slack:*' },
    { type: 'mrkdwn', text: username ? `<@${username}>` : 'Not found' },
  );
}

if (infoFields.length > 0) {
  blocks.push({
    type: 'section',
    text: { type: 'mrkdwn', text: '*Workflow and Commit Information*' },
    fields: infoFields,
  });
}

const payload = {
  attachments: [{ color: colorFor(result), blocks }],
};

writeFileSync('slack_payload.json', JSON.stringify(payload, null, 2));
