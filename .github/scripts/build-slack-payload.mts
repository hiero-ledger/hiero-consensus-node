// SPDX-License-Identifier: Apache-2.0
import {writeFileSync} from 'node:fs';
import {execSync} from 'node:child_process';


// types
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

// util functions
async function findSlackUsername(email: string, token: string): Promise<string | null> {
    const normalizedEmail = email.toLowerCase();
    let cursor: string | undefined;

    do {
        const params = new URLSearchParams({limit: '200'});
        if (cursor) params.set('cursor', cursor);

        const res = await fetch(`https://slack.com/api/users.list?${params}`, {
            headers: {Authorization: `Bearer ${token}`},
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

function gitLog(format: string): string {
    return execSync(`git log -1 --pretty=format:${format}`).toString().trim();
}

function colorFor(result: string): string {
    if (result === 'success') return '#00FF00';
    if (result === 'cancelled') return '#555555';
    return '#FF0000';
}

function validateEnvVar(varName: string):string {
    if(!(varName in process.env)) {
        console.error(`${varName} is a required environment variable`);
        process.exit(1)
    }
    return process.env[varName] as string
}

// input validation
const ref = validateEnvVar('NOTIFY_REF');
const slackApiToken= validateEnvVar('SLACK_API_TOKEN')
const repository = validateEnvVar('GITHUB_REPOSITORY')
const runId = validateEnvVar('GITHUB_RUN_ID')
const serverUrl = validateEnvVar('GITHUB_SERVER_URL')



const headerEmoji = process.env.HEADER_EMOJI ?? ':vertical_traffic_light:';
const headerText = process.env.HEADER_TEXT ?? '';
const blocks: Block[] = [
    {type: 'header', text: {type: 'plain_text', text: `${headerEmoji} ${headerText}`, emoji: true}},
    {type: 'divider'},
];

const result = process.env.RESULT ?? '';
const resultLabel = process.env.RESULT_LABEL ?? '';
if (result && resultLabel) {
    blocks.push(
        {
            type: 'section', fields: [
                {type: 'plain_text', text: resultLabel},
                {type: 'plain_text', text: result},
            ]
        },
        {type: 'divider'},
    );
}

const infoFields: TextField[] = [];

const commitUrl = `${serverUrl}/${repository}/commit/${ref}`
infoFields.push(
    {type: 'mrkdwn', text: '*Source Commit*:'},
    {type: 'mrkdwn', text: `<${commitUrl}>`},
);
infoFields.push(
    {type: 'mrkdwn', text: '*Workflow run ID*:'},
    {type: 'mrkdwn', text: runId},
);

const runUrl = `${serverUrl}/${repository}/actions/runs/${runId}`;
infoFields.push(
    {type: 'mrkdwn', text: '*Workflow run URL*:'},
    {type: 'mrkdwn', text: `<${runUrl}>`},
);

const authorEmail = gitLog('%ae');
const authorName = gitLog('%an');
infoFields.push(
    {type: 'mrkdwn', text: '*Commit Author*:'},
    {type: 'mrkdwn', text: `${authorName} <${authorEmail}>`},
);

const username = await findSlackUsername(authorEmail, slackApiToken);
infoFields.push(
    {type: 'mrkdwn', text: '*Slack:*'},
    {type: 'mrkdwn', text: username ? `<@${username}>` : 'Not found'},
);

blocks.push({
    type: 'section',
    text: {type: 'mrkdwn', text: '*Workflow and Commit Information*'},
    fields: infoFields,
});

const payload = {
    attachments: [{color: colorFor(result), blocks}],
};

writeFileSync('slack_payload.json', JSON.stringify(payload, null, 2));
console.log("generated", JSON.stringify(payload, null, 2));
