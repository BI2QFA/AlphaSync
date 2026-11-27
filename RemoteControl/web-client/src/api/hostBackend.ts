export interface HostCapabilities {
  backend: boolean;
  cameraUrl?: string;
  openfocus: {
    available: boolean;
    reason: string;
  };
}

export interface StackMergeStatus {
  sessionId: string;
  status: 'collecting' | 'merging' | 'done' | 'error';
  frameCount: number;
  error?: string | null;
}

const UNAVAILABLE_CAPS: HostCapabilities = {
  backend: false,
  openfocus: {
    available: false,
    reason: 'Host backend not detected. Start host-server/server.py and open the UI via it to enable OpenFocus.',
  },
};

export async function fetchHostCapabilities(): Promise<HostCapabilities> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 2000);
  try {
    const res = await fetch('/local-api/capabilities', {
      cache: 'no-store',
      signal: controller.signal,
    });
    if (!res.ok) return UNAVAILABLE_CAPS;
    const ct = res.headers.get('content-type') || '';
    if (!ct.includes('application/json')) return UNAVAILABLE_CAPS;
    const data = (await res.json()) as HostCapabilities;
    if (!data || typeof data.backend !== 'boolean') return UNAVAILABLE_CAPS;
    return data;
  } catch {
    return UNAVAILABLE_CAPS;
  } finally {
    window.clearTimeout(timer);
  }
}

export async function createBackendStackSession(
  sessionId: string,
  cameraUrl?: string,
): Promise<void> {
  const res = await fetch('/local-api/stacks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, cameraUrl }),
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data?.error || `Create stack session failed (HTTP ${res.status})`);
  }
}

export async function grabLatestToBackendStack(
  sessionId: string,
  cameraUrl?: string,
): Promise<number> {
  const res = await fetch(`/local-api/stacks/${encodeURIComponent(sessionId)}/grab-latest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cameraUrl }),
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data?.error || `Grab stack frame failed (HTTP ${res.status})`);
  }
  return Number(data.frameCount ?? 0);
}

export async function triggerBackendStackMerge(
  sessionId: string,
  expectedCount?: number,
): Promise<void> {
  const res = await fetch(`/local-api/stacks/${encodeURIComponent(sessionId)}/merge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(typeof expectedCount === 'number' ? { expectedCount } : {}),
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data?.error || `Start OpenFocus merge failed (HTTP ${res.status})`);
  }
}

export async function getBackendStackStatus(sessionId: string): Promise<StackMergeStatus> {
  const res = await fetch(`/local-api/stacks/${encodeURIComponent(sessionId)}`, {
    cache: 'no-store',
  });
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data?.error || `Get stack status failed (HTTP ${res.status})`);
  }
  return data as StackMergeStatus;
}

export function backendStackResultUrl(sessionId: string): string {
  return `/local-api/stacks/${encodeURIComponent(sessionId)}/result?ts=${Date.now()}`;
}

export async function deleteBackendStackSession(sessionId: string): Promise<void> {
  await fetch(`/local-api/stacks/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  }).catch(() => {});
}
