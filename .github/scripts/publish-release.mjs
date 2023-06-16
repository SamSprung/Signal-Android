import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const token = process.env.GITHUB_TOKEN;
const repository = process.env.GITHUB_REPOSITORY;
const commit = process.env.GITHUB_SHA;
const branch = process.env.GITHUB_REF_NAME;
const shortHash = process.env.GIT_HASH;
const assetPath = process.env.SIGNED_RELEASE_FILE;
const tag = `8bit/${branch}`;
const maxReleaseBodyLength = 100_000;

if (!token || !repository || !commit || !branch || !shortHash || !assetPath) {
  throw new Error('Missing required environment for release publishing.');
}

const apiRoot = `https://api.github.com/repos/${repository}`;

async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${apiRoot}/${path}`, {
    method,
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  if (response.status === 204) return undefined;

  const text = await response.text();
  const result = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new Error(`${method} ${path} failed (${response.status}): ${text}`);
  }
  return result;
}

async function findTagCommit() {
  let ref;
  try {
    ref = await api(`git/ref/tags/${tag}`);
  } catch (error) {
    if (error.message.includes('(404)')) return undefined;
    throw error;
  }

  let object = ref.object;
  while (object.type === 'tag') {
    object = (await api(`git/tags/${object.sha}`)).object;
  }
  return object.sha;
}

function makeReleaseNotes(commits) {
  const lines = commits
    .filter(({ commit: item }) => !/^Merge pull request #\d+ from /.test(item.message))
    .map(({ sha, commit: item }) => {
      const subject = item.message.split(/\r?\n/, 1)[0];
      const author = item.author?.name;
      return `- ${sha.slice(0, 7)}: ${subject}${author ? ` (${author})` : ''}`;
    });

  const fullNotes = lines.join('\n');
  if (fullNotes.length <= maxReleaseBodyLength) return fullNotes;

  const suffix = '\n\n(Release notes truncated to fit GitHub’s release body limit.)';
  return `${fullNotes.slice(0, maxReleaseBodyLength - suffix.length)}${suffix}`;
}

const previousCommit = await findTagCommit();
let releaseNotes = 'Initial automated release.';
if (previousCommit && previousCommit !== commit) {
  const comparison = await api(`compare/${previousCommit}...${commit}?per_page=250`);
  releaseNotes = makeReleaseNotes(comparison.commits);
}

console.log(`Prepared ${releaseNotes.split('\n').filter((line) => line.startsWith('- ')).length} release note entries (${releaseNotes.length} characters).`);

if (previousCommit) {
  await api(`git/refs/tags/${tag}`, {
    method: 'PATCH',
    body: { sha: commit, force: true },
  });
} else {
  await api('git/refs', {
    method: 'POST',
    body: { ref: `refs/tags/${tag}`, sha: commit },
  });
}

const releasePayload = {
  tag_name: tag,
  target_commitish: branch,
  name: `Signal-Android-${shortHash}`,
  body: releaseNotes,
  draft: false,
  prerelease: branch !== 'main',
};

let existingRelease;
try {
  existingRelease = await api(`releases/tags/${encodeURIComponent(tag)}`);
} catch (error) {
  if (!error.message.includes('(404)')) throw error;
}

if (existingRelease) {
  await api(`releases/${existingRelease.id}`, { method: 'DELETE' });
}

const release = await api('releases', {
  method: 'POST',
  body: releasePayload,
});

const assetName = basename(assetPath);
const uploadUrl = release.upload_url.replace(/\{\?.*$/, '');
const upload = new URL(uploadUrl);
upload.searchParams.set('name', assetName);
const assetBytes = await readFile(assetPath);
const uploaded = await fetch(upload, {
  method: 'POST',
  headers: {
    Accept: 'application/vnd.github+json',
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/vnd.android.package-archive',
  },
  body: assetBytes,
});
if (!uploaded.ok) {
  throw new Error(`Uploading ${assetName} failed (${uploaded.status}): ${await uploaded.text()}`);
}

console.log(`Published ${assetName} to ${release.html_url}`);
