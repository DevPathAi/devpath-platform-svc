import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const SERVICE = 'devpath-platform-svc';
const REPOSITORY = `DevPathAi/${SERVICE}`;
const IMAGE_REPOSITORY = `ghcr.io/devpathai/${SERVICE}`;
const WORKFLOW_PATH = '.github/workflows/ci.yml';

async function source(path) {
  return readFile(new URL(path, import.meta.url), 'utf8');
}

test('CI has a PR-safe build and a repository-bound main image producer', async () => {
  const workflow = await source('../workflows/ci.yml');

  assert.match(workflow, /pull_request:\s*(?:\r?\n|$)/);
  assert.match(workflow, /push:\s*\r?\n\s+branches:\s*\[main\]/);
  assert.match(workflow, /github\.event_name == 'push'/);
  assert.match(workflow, /github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, new RegExp(
      `github\\.repository == '${REPOSITORY.replace('/', '\\/')}'`));
  assert.match(workflow, /concurrency:[\s\S]*cancel-in-progress:\s*false/);
  assert.match(workflow, /runs-on:\s*ubuntu-24\.04/g);
  assert.doesNotMatch(workflow, /ubuntu-latest/);
});

test('all actions, service images, BuildKit, and the runtime base are immutable', async () => {
  const workflow = await source('../workflows/ci.yml');
  const dockerfile = await source('../../Dockerfile');

  const actions = workflow.split(/\r?\n/)
      .map((line) => line.match(
          /^[ \t]*(?:-[ \t]+)?uses:[ \t]+([^ \t#]+)/)?.[1])
      .filter(Boolean);
  assert.ok(actions.length >= 8, 'expected the full build and image action chain');
  for (const action of actions) {
    assert.match(action, /^[^@\s]+@[0-9a-f]{40}$/, `mutable action: ${action}`);
  }
  for (const [, image] of workflow.matchAll(/^\s{8}image:\s+([^\s]+)$/gm)) {
    assert.match(image, /@sha256:[0-9a-f]{64}$/, `mutable service image: ${image}`);
  }
  for (const [, image] of workflow.matchAll(/\bdocker pull\s+([^\s]+)/g)) {
    assert.match(image, /@sha256:[0-9a-f]{64}$/, `mutable pulled image: ${image}`);
  }
  assert.match(workflow,
      /image=moby\/buildkit:v0\.30\.0@sha256:[0-9a-f]{64}/);
  assert.match(dockerfile,
      /^FROM eclipse-temurin:21-jre-alpine@sha256:[0-9a-f]{64} AS runtime$/m);
});

test('the producer always builds a local candidate before absent-or-exact binding', async () => {
  const workflow = await source('../workflows/ci.yml');

  const preflight = workflow.indexOf('Inspect immutable source tag before build');
  const candidate = workflow.indexOf('Build exact local image candidate');
  const recheck = workflow.indexOf('Recheck immutable tag immediately before binding');
  const binding = workflow.indexOf('Bind immutable source tag once');
  const postflight = workflow.indexOf('Verify published digest and OCI identity');
  const stable = workflow.indexOf('Reopen immutable digest and preserve sanitized evidence');
  assert.ok(preflight >= 0 && preflight < candidate);
  assert.ok(candidate < recheck && recheck < binding);
  assert.ok(binding < postflight && postflight < stable);

  assert.match(workflow, /push:\s*false/);
  assert.match(workflow, /platforms:\s*linux\/amd64/);
  assert.match(workflow,
      /\.rootfs\.diff_ids \| unique \| length.*\.rootfs\.diff_ids \| length/);
  assert.match(workflow, /org\.opencontainers\.image\.revision=\$\{\{ github\.sha \}\}/);
  assert.match(workflow,
      /org\.opencontainers\.image\.source=https:\/\/github\.com\/\$\{\{ github\.repository \}\}/);
  assert.match(workflow,
      new RegExp(`TAG_REFERENCE:\\s*${IMAGE_REPOSITORY.replaceAll('/', '\\/')}:\\$\\{\\{ github\\.sha \\}\\}`));
});

test('mutable tags and source-repository GitOps writes are absent', async () => {
  const workflow = await source('../workflows/ci.yml');

  assert.doesNotMatch(workflow, /ghcr\.io\/[^\s]+:(?:main|latest)(?:\s|$)/);
  assert.doesNotMatch(workflow, /GITOPS_APP|devpath-gitops|create-github-app-token/i);
  assert.doesNotMatch(workflow, /^\s{2}deploy:\s*$/m);
  assert.doesNotMatch(workflow, /kustomize edit|git push|git pull --rebase/i);
});

test('one non-overwriting evidence file is scoped to source, run, and first attempt', async () => {
  const workflow = await source('../workflows/ci.yml');
  const expectedName = `${SERVICE}-immutable-image-\${{ github.sha }}-run-\${{ github.run_id }}-attempt-\${{ github.run_attempt }}`;

  assert.ok(workflow.includes(`name: ${expectedName}`));
  assert.match(workflow, /path:\s*\$\{\{ runner\.temp \}\}\/evidence\.json/);
  assert.equal(workflow.match(/--evidence/g)?.length, 1);
  assert.equal(workflow.match(/runner\.temp \}\}\/evidence\.json/g)?.length, 2);
  assert.match(workflow, /overwrite:\s*false/);
  assert.match(workflow, /if-no-files-found:\s*error/);
  assert.match(workflow, /PRODUCER_WORKFLOW_PATH:\s*\.github\/workflows\/ci\.yml/);
  assert.match(workflow, /test "\$\{\{ github\.run_attempt \}\}" = "1"/);
  assert.match(workflow,
      /git show "\$\{\{ github\.sha \}\}:\.github\/workflows\/ci\.yml"/);
  assert.match(workflow, /cmp --silent \.github\/workflows\/ci\.yml/);
  for (const line of workflow.split(/\r?\n/).filter((value) => /run:/.test(value))) {
    assert.doesNotMatch(line, /REGISTRY_(?:USERNAME|PASSWORD)/);
  }
});

test('workflow path contract stays stable for the GitOps consumer', () => {
  assert.equal(WORKFLOW_PATH, '.github/workflows/ci.yml');
});

test('evidence creation is local no-overwrite as well as artifact no-overwrite', async () => {
  const helper = await source('./immutable-image-registry.mjs');
  assert.match(helper, /mode:\s*0o600,[\s\S]*flag:\s*'wx'/);
});
