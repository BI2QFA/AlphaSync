export const ZOOM_TELE = 0;
export const ZOOM_WIDE = 1;

export type RpcTarget =
  | 'camera'
  | 'modifier'
  | 'params'
  | 'static:System'
  | 'static:Temperature'
  | 'static:ScalarProperties'
  | string;

export interface RpcResult<T = unknown> {
  ok: boolean;
  target: string;
  method: string;
  result?: T;
  error?: string;
}

export type RawMode = 'compress' | 'uncompress';

export interface RawSettings {
  mode: string | null;
  options: RawMode[];
}

export interface CameraState {
  ok: boolean;
  seq: number;
  aperture?: {
    currentAperture?: number;
    currentAvailableMin?: number;
    currentAvailableMax?: number;
  };
  shutter?: {
    currentShutterSpeed_n?: number;
    currentShutterSpeed_d?: number;
  };
  focus?: {
    currentPosition?: number;
    maxPosition?: number;
  };
  zoom?: {
    opticalPosition?: number;
    opticalMagnification?: number;
    digitalPosition?: number;
    digitalMagnification?: number;
    stopped?: boolean;
  };
  autoIso?: number;
  isoSetting?: number;
  sceneMode?: string;
  rawRecMode?: string;
  focusSettings?: {
    mode?: string; modes: string[];
    area?: string; areas?: string[];
    spotSize?: string; spotSizes?: string[];
  };
  focalLength?: number;
  powerZoom?: boolean | number;
  previewMagnification?: {
    enabled?: boolean;
    ratio?: number;
    pos?: [number, number] | number[];
  };
  lastStoreImage?: {
    imageId?: number;
    file?: string;
  };
  lastShutterStatus?: number;
  capturing?: boolean;
  lens?: {
    LensName?: string;
    LensType?: string;
    FocalLength?: string | { tele?: number; wide?: number };
    MinFValue?: string | { tele?: number; wide?: number };
    MaxFValue?: string | { tele?: number; wide?: number };
  };
  wifi?: string | { ip?: string; port?: number };
  exposureCompensation?:
    | {
        index?: number;
        step?: number;
        min?: number;
        max?: number;
      }
    | number;
}

export interface CameraEvent {
  seq: number;
  t: number;
  type: string;
  data?: Record<string, unknown>;
}

export interface EventsResponse {
  ok: boolean;
  seq: number;
  events: CameraEvent[];
}

export interface DescribeMethod {
  name: string;
  params: string[];
  returnType: string;
}

export interface DescribeResponse {
  ok: boolean;
  targets: Record<string, DescribeMethod[]>;
}

export interface DirEntry {
  name: string;
  path: string;
}

export interface PhotoEntry {
  name: string;
  path: string;
  size: number;
}

export interface PhotosResponse {
  ok: boolean;
  dir: string;
  totalPhotosInDir: number;
  dirs: DirEntry[];
  photos: PhotoEntry[];
}

export interface AutoFocusResult {
  focused: boolean;
  superseded: boolean;
  timedOut: boolean;
  x?: number;
  y?: number;
  position?: number;
}

export interface SetFocusResult {
  converged: boolean;
  superseded: boolean;
  stalled: boolean;
  position: number;
  target: number;
  steps: number;
  manualOverride?: boolean;
  unstableDirection?: boolean;
}

const STORAGE_KEY = 'pmca_camera_base_url';

export function getDefaultCameraUrl(): string {
  if (__CAMERA_EMBEDDED__) return window.location.origin + window.location.pathname.replace(/\/+$/, "");

  const isHttpsPage =
    typeof window !== 'undefined' && window.location.protocol === 'https:';
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      if (!(isHttpsPage && saved.startsWith('http://'))) {
        return saved;
      }
    }
  } catch {
    // ignore storage errors
  }
  if (
    typeof window !== 'undefined' &&
    (isHttpsPage || window.location.port === '8080') &&
    window.location.protocol.startsWith('http')
  ) {
    return window.location.origin;
  }
  return 'http://192.168.0.250:8080';
}

export function saveCameraUrl(url: string): string {
  const normalized = url.trim().replace(/\/+$/, '');
  try {
    localStorage.setItem(STORAGE_KEY, normalized);
  } catch {
    // ignore
  }
  return normalized;
}

export class CameraClient {
  private baseUrl: string;

  constructor(baseUrl?: string) {
    this.baseUrl = (baseUrl ?? getDefaultCameraUrl()).replace(/\/+$/, '');
  }

  setBaseUrl(url: string): void {
    this.baseUrl = saveCameraUrl(url);
  }

  private async requestJson<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init?.headers ?? {}),
      },
    });
    const data = (await res.json()) as T & { ok?: boolean; error?: string };
    if (!res.ok || data.ok === false) {
      throw new Error(data.error || `HTTP ${res.status} ${res.statusText}`);
    }
    return data;
  }

  async call<T = unknown>(
    target: RpcTarget,
    method: string,
    args: unknown[] = [],
    signal?: AbortSignal,
  ): Promise<T> {
    const data = await this.requestJson<RpcResult<T>>('/api/call', {
      method: 'POST',
      body: JSON.stringify({ target, method, args }),
      signal,
    });
    return data.result as T;
  }

  async describe(target?: string, q?: string): Promise<DescribeResponse> {
    const params = new URLSearchParams();
    if (target) params.set('target', target);
    if (q) params.set('q', q);
    const qs = params.toString();
    return this.requestJson<DescribeResponse>(`/api/describe${qs ? `?${qs}` : ''}`);
  }

  async getState(signal?: AbortSignal): Promise<CameraState> {
    return this.requestJson<CameraState>('/api/state', { signal });
  }

  async pollEvents(
    since: number,
    timeoutSec = 10,
    signal?: AbortSignal,
  ): Promise<EventsResponse> {
    return this.requestJson<EventsResponse>(
      `/api/events?since=${since}&timeout=${timeoutSec}`,
      { signal },
    );
  }

  eventsStreamUrl(since = 0, cid?: string): string {
    const params = new URLSearchParams({ since: String(since) });
    if (cid) params.set('cid', cid);
    return `${this.baseUrl}/api/events/stream?${params.toString()}`;
  }

  eventsCloseUrl(cid: string): string {
    return `${this.baseUrl}/api/events/close?cid=${encodeURIComponent(cid)}`;
  }

  closeEventsStream(cid: string): void {
    this.sendCloseBeacon(this.eventsCloseUrl(cid));
  }

  async listPhotos(dir = '', limit = 100): Promise<PhotosResponse> {
    const params = new URLSearchParams();
    if (dir) params.set('dir', dir);
    params.set('limit', String(limit));
    return this.requestJson<PhotosResponse>(`/api/photos?${params.toString()}`);
  }

  liveViewMjpegUrl(cid?: string): string {
    const query = cid ? `?cid=${encodeURIComponent(cid)}` : '';
    return `${this.baseUrl}/api/liveview.mjpeg${query}`;
  }

  liveViewJpgUrl(ts = Date.now()): string {
    return `${this.baseUrl}/api/liveview.jpg?t=${ts}`;
  }

  liveViewCloseUrl(cid: string): string {
    return `${this.baseUrl}/api/liveview/close?cid=${encodeURIComponent(cid)}`;
  }

  closeLiveView(cid: string): void {
    this.sendCloseBeacon(this.liveViewCloseUrl(cid));
  }

  private sendCloseBeacon(url: string): void {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      if (navigator.sendBeacon(url)) {
        return;
      }
    }
    void fetch(url, { method: 'POST', keepalive: true }).catch(() => {});
  }

  latestPhotoUrl(ts = Date.now(), inline = false): string {
    return `${this.baseUrl}/api/photos/latest?t=${ts}${inline ? '&inline=1' : ''}`;
  }

  latestPhotoPreviewUrl(ts: number): string {
    return `${this.latestPhotoUrl(ts)}&preview=1`;
  }

  storedPhotoUrl(directory: string, name: string): string {
    const query = new URLSearchParams({ storedDir: directory, storedName: name });
    return `${this.baseUrl}/api/photos/latest?${query}`;
  }

  photoDownloadUrl(path: string): string {
    return `${this.baseUrl}/api/photos/download?path=${encodeURIComponent(path)}`;
  }

  photoPreviewUrl(path: string): string {
    return `${this.photoDownloadUrl(path)}&preview=1`;
  }

  // --- Convenience Camera Controls ---

  takePicture(): Promise<unknown> {
    if (!__CAMERA_EMBEDDED__) return this.call('camera', 'burstableTakePicture');
    return this.requestJson('/api/capture', {
      method: 'POST',
      signal: AbortSignal.timeout(10000),
    });
  }

  incrementAperture(): Promise<unknown> {
    return this.call('camera', 'incrementAperture');
  }

  decrementAperture(): Promise<unknown> {
    return this.call('camera', 'decrementAperture');
  }

  incrementShutter(): Promise<unknown> {
    return this.call('camera', 'incrementShutterSpeed');
  }

  decrementShutter(): Promise<unknown> {
    return this.call('camera', 'decrementShutterSpeed');
  }

  setIso(iso: number): Promise<unknown> {
    return this.call('modifier', 'setISOSensitivity', [iso]);
  }

  getRawSettings(signal?: AbortSignal): Promise<RawSettings> {
    return this.call<RawSettings>('rawSettings', 'get', [], signal);
  }

  async setRawMode(mode: RawMode, signal: AbortSignal): Promise<RawSettings> {
    await this.call('rawSettings', 'set', [mode], signal);
    let actual = await this.getRawSettings(signal);
    for (let attempt = 0; attempt < 4 && actual.mode !== mode; attempt++) {
      await waitAbortable(200, signal);
      actual = await this.getRawSettings(signal);
    }
    return actual;
  }

  async getFocusSettings(): Promise<NonNullable<CameraState['focusSettings']>> {
    const response = await this.requestJson<RpcResult<NonNullable<CameraState['focusSettings']>>>('/api/call', {
      method: 'POST',
      body: JSON.stringify({ target: 'focusSettings', method: 'get', args: [] }),
    });
    if (!response.result) throw new Error('相机未提供对焦设置');
    return response.result;
  }

  async setFocusSetting(key: 'mode' | 'area' | 'spotSize', value: string): Promise<NonNullable<CameraState['focusSettings']>> {
    await this.requestJson('/api/call', {
      method: 'POST',
      body: JSON.stringify({ target: 'focusSettings', method: 'set', args: [key, value] }),
    });
    for (let attempt = 0; attempt < 5; attempt++) {
      await waitAbortable(200);
      const settings = (await this.getState()).focusSettings;
      if (settings && (settings[key] === value ||
          (key === 'mode' && value === 'auto' && settings.mode?.startsWith('auto:')))) return settings;
    }
    if (key === 'spotSize') throw new Error('相机未应用所选自由点大小，界面保留相机当前值');
    throw new Error(key === 'area'
      ? '相机未应用所选对焦区域，界面保留相机当前值'
      : '相机未应用所选对焦模式，请检查机身或镜头的 AF/MF 设置');
  }

  getSupportedISOSensitivities(): Promise<number[]> {
    return this.call<number[]>('modifier', 'getSupportedISOSensitivities');
  }

  getExposureCompensation(): Promise<number> {
    return this.call<number>('params', 'getExposureCompensation');
  }

  setExposureCompensation(index: number): Promise<unknown> {
    return this.call('params', 'setExposureCompensation', [index]);
  }

  getSceneMode(): Promise<string> {
    return this.call<string>('params', 'getSceneMode');
  }

  setSceneMode(mode: string): Promise<unknown> {
    return this.call('params', 'setSceneMode', [mode]);
  }

  getWhiteBalance(): Promise<string> {
    return this.call<string>('params', 'getWhiteBalance');
  }

  setWhiteBalance(mode: string): Promise<unknown> {
    return this.call('params', 'setWhiteBalance', [mode]);
  }

  getSupportedWhiteBalance(): Promise<string[]> {
    return this.call<string[]>('params', 'getSupportedWhiteBalance');
  }

  getColorTemperature(): Promise<number> {
    return this.call<number>('modifier', 'getColorTemperatureForWhiteBalance');
  }

  setColorTemperature(kelvin: number): Promise<unknown> {
    return this.call('modifier', 'setColorTemperatureForWhiteBalance', [kelvin]);
  }

  getWhiteBalanceLightBalance(): Promise<number> {
    return this.call<number>('modifier', 'getLightBalanceForWhiteBalance');
  }

  setWhiteBalanceLightBalance(value: number): Promise<unknown> {
    return this.call('modifier', 'setLightBalanceForWhiteBalance', [value]);
  }

  getWhiteBalanceColorComp(): Promise<number> {
    return this.call<number>('modifier', 'getColorCompensationForWhiteBalance');
  }

  setWhiteBalanceColorComp(value: number): Promise<unknown> {
    return this.call('modifier', 'setColorCompensationForWhiteBalance', [value]);
  }

  startZoom(direction: 0 | 1, speed = 1): Promise<unknown> {
    return this.call('camera', 'startZoom', [direction, speed]);
  }

  stopZoom(): Promise<unknown> {
    return this.call('camera', 'stopZoom');
  }

  async zoomStep(direction: 0 | 1, speed = 1): Promise<void> {
    await this.startZoom(direction, speed);
    await new Promise((r) => setTimeout(r, 90));
    await this.stopZoom();
  }

  setZoomPosition(opticalPosition: number, tolerance = 1): Promise<{ position: number; converged: boolean; superseded: boolean }> {
    return this.call('highlevel', 'setZoomPosition', [opticalPosition, tolerance]);
  }

  setAperture(fValue100: number): Promise<{ position: number; converged: boolean; superseded: boolean }> {
    return this.call('highlevel', 'setAperture', [fValue100]);
  }

  setShutterSpeed(n: number, d: number): Promise<{ currentShutterSpeed_n: number; currentShutterSpeed_d: number; converged: boolean; superseded: boolean }> {
    return this.call('highlevel', 'setShutterSpeed', [n, d]);
  }

  autoFocusAt(normX: number, normY: number): Promise<AutoFocusResult> {
    return this.call<AutoFocusResult>('highlevel', 'autoFocusAt', [normX, normY]);
  }

  setPreviewMagnification(factor: 100 | 200, x: number, y: number): Promise<unknown> {
    return this.call('camera', 'setPreviewMagnification', [factor, [x, y]]);
  }

  stopPreviewMagnification(): Promise<unknown> {
    return this.call('camera', 'stopPreviewMagnification');
  }

  /**
   * Device-side closed-loop focus positioning via `highlevel.setFocusPosition`.
   */
  async gotoFocus(
    targetPos: number,
    tolerance = 0,
    signal?: AbortSignal,
  ): Promise<number> {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const res = await this.call<SetFocusResult>(
      'highlevel',
      'setFocusPosition',
      [targetPos, tolerance],
      signal,
    );
    if (signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError');
    }
    if (res.unstableDirection || res.manualOverride) {
      throw new Error(`镜头位置反馈方向不稳定，停在 ${res.position}，目标 ${res.target}`);
    }
    if (res.superseded) {
      throw new DOMException('Superseded', 'AbortError');
    }
    if (!res.converged || Math.abs(res.position - res.target) > tolerance) {
      throw new Error(
        `镜头未到达焦点位置 ${res.target}，停在 ${res.position}${res.stalled ? '（未检测到镜头移动）' : ''}。请用能实际到达的两个位置设置起点和终点。`,
      );
    }
    return res.position;
  }

  async runFocusStack(
    startPos: number,
    endPos: number,
    stepSize: number,
    settleMs: number,
    onProgress: (info: { index: number; total: number; targetPos: number; actualPos: number }) => void,
    signal?: AbortSignal,
    onTarget?: (targetPos: number) => void,
    onCaptureShot?: (triggerShot: () => Promise<unknown>, signal?: AbortSignal) => Promise<void>,
  ): Promise<void> {
    const positions = computeFocusStackPositions(startPos, endPos, stepSize);
    let previousPosition: number | undefined;
    for (let i = 0; i < positions.length; i++) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const target = positions[i];
      onTarget?.(target);
      const gap = Math.min(
        i > 0 ? Math.abs(target - positions[i - 1]) : Infinity,
        i + 1 < positions.length ? Math.abs(target - positions[i + 1]) : Infinity,
      );
      const tolerance = Math.min(2, Math.floor((gap - 1) / 2));
      const actual = await this.gotoFocus(target, tolerance, signal);
      if (previousPosition !== undefined &&
          (actual - previousPosition) * Math.sign(endPos - startPos) <= 0) {
        throw new Error(`镜头未前进到新的拍摄位置，停在 ${actual}。请增大焦点步长。`);
      }
      previousPosition = actual;
      onProgress({ index: i + 1, total: positions.length, targetPos: target, actualPos: actual });
      await waitAbortable(settleMs, signal);
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      if (onCaptureShot) {
        await onCaptureShot(() => this.takePicture(), signal);
      } else {
        await this.takePicture();
        await waitAbortable(900, signal);
      }
    }
  }
}

function waitAbortable(ms: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) {
    return Promise.reject(new DOMException('Aborted', 'AbortError'));
  }
  if (ms <= 0) return Promise.resolve();
  return new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export function computeFocusStackPositions(
  startPos: number,
  endPos: number,
  stepSize: number,
): number[] {
  const start = Math.round(startPos);
  const end = Math.round(endPos);
  const step = Math.max(1, Math.round(stepSize));
  if (start === end) return [start];
  const dir = end > start ? 1 : -1;
  const positions: number[] = [];
  for (let pos = start; dir > 0 ? pos < end : pos > end; pos += dir * step) {
    positions.push(pos);
  }
  positions.push(end);
  return positions;
}

export function formatAperture(raw?: number): string {
  if (!raw || raw <= 0) return '--';
  return `F${(raw / 100).toFixed(1).replace(/\.0$/, '')}`;
}

export function formatShutter(n?: number, d?: number): string {
  if (!n || !d) return '--';
  if (n >= d) {
    const sec = n / d;
    return `${Number.isInteger(sec) ? sec : sec.toFixed(1)}s`;
  }
  return `1/${Math.round(d / n)}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

export function formatEv(index?: number, step = 1 / 3): string {
  if (typeof index !== 'number' || Number.isNaN(index)) return '--';
  const ev = Math.round(index * step * 10) / 10;
  if (Math.abs(ev) < 0.05) return '±0.0 EV';
  const sign = ev > 0 ? '+' : '';
  return `${sign}${ev.toFixed(1)} EV`;
}
